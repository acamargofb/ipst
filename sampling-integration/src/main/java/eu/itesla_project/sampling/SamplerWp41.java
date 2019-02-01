/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package eu.itesla_project.sampling;

import com.jmatio.io.MatFileReader;
import com.jmatio.types.MLArray;
import com.jmatio.types.MLChar;
import com.jmatio.types.MLDouble;
import com.powsybl.computation.*;
import com.powsybl.iidm.network.Network;
import eu.itesla_project.modules.commons.io.CacheManager;
import eu.itesla_project.modules.histo.HistoDbClient;
import eu.itesla_project.modules.sampling.*;
import eu.itesla_project.sampling.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 *
 * @author Quinary <itesla@quinary.com>
 */
public class SamplerWp41 implements Sampler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamplerWp41.class);

    private static final String WP41_CACHEDIR_NAME = "wp41_v67";
    private static final String MODULE1_PREFIX = "mod1_";
    private static final String MODULE2_PREFIX = "mod2_";

    private static final String WP41B_IS = "wp41b_is";
    private static final String WP41C_M3 = "wp41c_v67_m3";
    private static final String WP41C_M3PRE = "wp41c_v67_m3pre";
    private static final String WP41C_M3_REDUCE = "wp41c_v67_aggregator";
    private static final String WP41C_M2 = "wp41c_v67_m2";
    private static final String WP41C_M1 = "wp41c_v67_m1";

    private static final String M1INPUTFILENAME = "m1input.mat";
    private static final String M3OUTPUTFILENAME = "m3output.mat";
    private static final String M1STATVARSFILENAME = "MOD1_statvars.mat";
    private static final String M3NSAMCFILENAME = "MOD3_nsamc.mat";
    private static final String B1INPUTFILENAME = "b1input.mat";
    private static final String B1OUTPUTFILENAME = "b1output.mat";
    private static final String WORKING_DIR_PREFIX = "itesla_sampler_";

    private final ComputationManager computationManager;

    private final int priority;

    private final HistoDbClient histoClient;

    private final SamplerWp41Config config;

    private final Network network;

    private DataMiningFacadeParams dmParams;

    private int nClusters = 0;

    public SamplerWp41(Network network, ComputationManager computationManager, int priority, HistoDbClient histoClient, SamplerWp41Config config) {
        this.config = Objects.requireNonNull(config);
        this.network = Objects.requireNonNull(network);
        this.computationManager = Objects.requireNonNull(computationManager);
        this.priority = priority;
        this.histoClient = Objects.requireNonNull(histoClient);
        LOGGER.info(config.toString());
    }

    public SamplerWp41(Network network, ComputationManager computationManager, int priority, HistoDbClient histoClient) {
        this(network, computationManager, priority, histoClient, SamplerWp41Config.load());
    }

    @Override
    public String getName() {
        return "Imperial College WP4.1";
    }

    @Override
    public String getVersion() {
        return "v6.7";
    }

    public SamplerWp41Config getConfig() {
        return config;
    }

    private Path getCacheDir(DataMiningFacadeParams dmParams) throws IOException {
        return CacheManager.defaultCacheManager().newCacheEntry(WP41_CACHEDIR_NAME)
                .withKey(Double.toString(config.getIr()))
                .withKey(Integer.toString(config.getPar_k()))
                .withKey(Double.toString(config.getTflag()))
                .withKey(dmParams.getInterval().toString())
                .withKeys(dmParams.getCountries().stream().map(Object::toString).collect(Collectors.toList()))
                .withKeys(dmParams.getGensIds())
                .withKeys(dmParams.getLoadsIds())
                .withKeys(dmParams.getDanglingLinesIds())
                .build()
                .create();
    }

    private static int countFiles(Path dir, String pattern) {
        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, pattern)) {
            for (@SuppressWarnings("unused") Path path : ds) {
                count++;
            }
        } catch (IOException ex) {
            LOGGER.error(ex.toString(), ex);
        }
        return count;
    }

    /*
     * executes wp41 module1 and module2, when data is not in cache
     */
    @Override
    public void init(SamplerParameters parameters) throws Exception {
        Objects.requireNonNull(parameters, "sampler parameter is null");

        dmParams = new DataMiningFacadeParams(network, parameters.isGenerationSampled(), parameters.isBoundariesSampled(), parameters.getHistoInterval());

        Path cacheDir = getCacheDir(dmParams);

        if (!(Files.exists(cacheDir.resolve("MOD1_0.mat")) && Files.exists(cacheDir.resolve("MOD2_0.mat")))) {
            computeMod1AndMod2(dmParams, cacheDir);
        } else {
            LOGGER.info("Cache found in {}, skipping module1 and module2 computation", cacheDir);
        }

        if (config.getValidationDir() != null) {
            Files.createDirectories(config.getValidationDir());
        }

        //infer the number of clusters from number module2 output files ....
        //tbd: find a more appropriate way to retrieve this number (store it in .mat, or in a csv, ...    at the end of module1&module2 execution)
        nClusters = countFiles(cacheDir, "MOD2_*.mat");
        for (int i = 0; i < nClusters; i++) {
            Path mod1FilePath = cacheDir.resolve("MOD1_" + i + ".mat");
            Path mod2FilePath = cacheDir.resolve("MOD2_" + i + ".mat");
            try (OutputStream os = computationManager.newCommonFile(mod1FilePath.getFileName().toString())) {
                Files.copy(mod1FilePath, os);
            }
            try (OutputStream os = computationManager.newCommonFile(mod2FilePath.getFileName().toString())) {
                Files.copy(mod2FilePath, os);
            }
        }
        Path mod1StatVarsFilePath = cacheDir.resolve(M1STATVARSFILENAME);
        try (OutputStream os = computationManager.newCommonFile(mod1StatVarsFilePath.getFileName().toString())) {
            Files.copy(mod1StatVarsFilePath, os);
        }
    }

    @Override
    public SamplerResult sample(int n, SampleIdGenerator idGenerator) throws Exception {
        boolean ok = false;
        List<Sample> ss = new ArrayList<>(n);
        try {
            double[][] samples = computeModule3(n);
            if (samples != null) {
                for (int sampleIndex = 0; sampleIndex < n; sampleIndex++) {
                    double[] sample = samples[sampleIndex];
                    //LOGGER.debug("sample vector size: {}", sample.length);
                    SampleImpl s = new SampleImpl(idGenerator.newId());
                    // 1st step: generators
                    for (int i = 0; i < dmParams.getGensIds().size(); i++) {
                        int genInd = i * 2;
                        String genId = dmParams.getGensIds().get(i);
                        float newP = (float) sample[genInd];
                        float newQ = (float) sample[genInd + 1];
                        s.addGenerator(genId, newP, newQ);
                    }
                    // 2nd step: loads
                    int offsetGens = dmParams.getGensIds().size() * 2;
                    for (int i = 0; i < dmParams.getLoadsIds().size(); i++) {
                        int loadInd = offsetGens + i * 2;
                        String loadId = dmParams.getLoadsIds().get(i);
                        float newP0 = (float) sample[loadInd];
                        float newQ0 = (float) sample[loadInd + 1];
                        s.addLoad(loadId, newP0, newQ0);
                    }
                    // 3rd step : dangling lines
                    int offsetLoads = offsetGens + dmParams.getLoadsIds().size() * 2;
                    for (int i = 0; i < dmParams.getDanglingLinesIds().size(); i++) {
                        int dlInd = offsetLoads + i * 2;
                        String dlId = dmParams.getDanglingLinesIds().get(i);
                        float newP0 = (float) sample[dlInd];
                        float newQ0 = (float) sample[dlInd + 1];
                        s.addDanglingLine(dlId, newP0, newQ0);
                    }
                    ss.add(s);
                }
                ok = true;
            }
        } catch (MatlabException e) {
            LOGGER.error(e.toString(), e);
        }
        return new SamplerResultImpl(ok, ss);
    }

    private DataMiningFacade getDataMiningFacade() {
        return new DataMiningFacadeHistodb(histoClient);
    }

    public Wp41HistoData getHistoDBData(DataMiningFacadeParams dmParams, Path workingDir) throws Exception {
        Wp41HistoData hdata = null;
        try {
            DataMiningFacade dmf = getDataMiningFacade();
            hdata = dmf.getDataFromHistoDatabase(dmParams);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(
                    "could not get historical data from histodb service: "
                            + t.getMessage());
        }
        if ((hdata == null) || ((hdata.getHdTable().columnKeySet().size() + hdata.getHdTable().rowKeySet().size()) == 0)) {
            throw new RuntimeException(
                    "could not find any data in the historical database");
        }
        return hdata;
    }

    private Map<String, String> createEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("LD_LIBRARY_PATH", config.getRuntimeHomeDir().resolve("runtime").resolve("glnxa64").toString()
                + ":" + config.getRuntimeHomeDir().resolve("bin").resolve("glnxa64").toString()
                + ":" + config.getRuntimeHomeDir().resolve("sys").resolve("os").resolve("glnxa64").toString());
        return env;
    }

    private Command createMatm1Cmd(int clustNums) {
        List<String> args1 = new ArrayList<>(6);
        args1.add(M1INPUTFILENAME);
        args1.add("./");
        args1.add("" + clustNums);
        if (config.getRngSeed() != null) {
            args1.add(Integer.toString(config.getRngSeed()));
        } else {
            args1.add(Integer.toString(0));
        }

        String wp41cM1;
        if (config.getBinariesDir() != null) {
            wp41cM1 = config.getBinariesDir().resolve(WP41C_M1).toAbsolutePath().toString();
        } else {
            wp41cM1 = WP41C_M1;
        }

        List<OutputFile> m1OutputFilesList = new ArrayList<>();
        for (int i = 0; i < clustNums; i++) {
            m1OutputFilesList.add(new OutputFile("MOD1_" + i + ".mat"));

        }
        m1OutputFilesList.add(new OutputFile(M1STATVARSFILENAME));
        return new SimpleCommandBuilder()
                .id(WP41C_M1)
                .program(wp41cM1)
                .args(args1)
                .inputFiles(new InputFile(M1INPUTFILENAME))
                .outputFiles(m1OutputFilesList)
                .build();
    }

    private Command createMatm2Cmd() {
        String wp41cM2;
        if (config.getBinariesDir() != null) {
            wp41cM2 = config.getBinariesDir().resolve(WP41C_M2).toAbsolutePath().toString();
        } else {
            wp41cM2 = WP41C_M2;
        }

        return new SimpleCommandBuilder()
        .id(WP41C_M2)
        .program(wp41cM2)
        .args("MOD1_${EXEC_NUM}.mat",
                "MOD2_${EXEC_NUM}.mat",
                CommandConstants.EXECUTION_NUMBER_PATTERN,
                "" + config.getIr(),
                "" + config.getTflag())
        .inputFiles(new InputFile("MOD1_${EXEC_NUM}.mat"))
        .outputFiles(new OutputFile("MOD2_${EXEC_NUM}.mat"))
        .build();

    }

    private void computeMod1AndMod2(DataMiningFacadeParams dmParams, Path cacheDir) throws Exception {
        computationManager.execute(new ExecutionEnvironment(createEnv(), WORKING_DIR_PREFIX, config.isDebug()), new AbstractExecutionHandler<Object>() {
            int parK;
            @Override
            public List<CommandExecution> before(Path workingDir) throws IOException {
                LOGGER.info("Retrieving historical data for network {}", network.getId());
                Wp41HistoData histoData = null;
                try {
                    histoData = getHistoDBData(dmParams, workingDir);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                parK = config.getPar_k() == -1 ? (int) Math.round(Math.sqrt(histoData.getHdTable().rowKeyList().size() / 2))
                        : config.getPar_k();
                LOGGER.info(" IR: {}, tflag: {}, number of clusters: {} ", config.getIr(), config.getTflag(), parK);
                double[][] dataMatrix = Utils.histoDataAsDoubleMatrixNew(histoData.getHdTable());
                Utils.writeWp41ContModule1Mat(workingDir.resolve(M1INPUTFILENAME), dataMatrix);

                if (config.getValidationDir() != null) {
                    // store input file, for validation purposes
                    try {
                        Files.copy(workingDir.resolve(M1INPUTFILENAME), config.getValidationDir().resolve(M1INPUTFILENAME), REPLACE_EXISTING);
                        Utils.dumpWp41HistoDataColumns(histoData, config.getValidationDir());
                    } catch (Throwable t) {
                        LOGGER.error(t.getMessage(), t);
                    }
                }

                LOGGER.info("Executing wp41 module1(once), module2 ({} times)", parK);
                return Arrays.asList(new CommandExecution(createMatm1Cmd(parK), 1, priority),
                        new CommandExecution(createMatm2Cmd(), parK, priority));
            }

            @Override
            public Object after(Path workingDir, ExecutionReport report) throws IOException {
                report.log();
                if (report.getErrors().size() > 0) {
                    throw new RuntimeException("Module 1 failed");
                }
                //1 brings all module1 output files (one per cluster plus the statvar file) back to the cache
                for (int i = 0; i < parK; i++) {
                    Path srcPath = workingDir.resolve("MOD1_" + i + ".mat");
                    Path destPath = cacheDir.resolve("MOD1_" + i + ".mat");
                    Files.copy(srcPath, destPath, REPLACE_EXISTING);
                }
                Files.copy(workingDir.resolve(M1STATVARSFILENAME), cacheDir.resolve(M1STATVARSFILENAME), REPLACE_EXISTING);
                for (int i = 0; i < parK; i++) {
                    Path srcPath = workingDir.resolve("MOD2_" + i + ".mat");
                    Path destPath = cacheDir.resolve("MOD2_" + i + ".mat");
                    Files.copy(srcPath, destPath, REPLACE_EXISTING);
                }
                return null;
            }
        }).join();
    }

    private Command createMatm3PreCmd(int clustNums, int samplesSize) {
        List<String> args1 = new ArrayList<>(6);
        args1.add(M1STATVARSFILENAME);
        args1.add(M3NSAMCFILENAME);
        args1.add("" + clustNums);
        args1.add("" + samplesSize);
        if (config.getRngSeed() != null) {
            args1.add(Integer.toString(config.getRngSeed()));
        } else {
            args1.add(Integer.toString(0));
        }

        String wp41cM3pre;
        if (config.getBinariesDir() != null) {
            wp41cM3pre = config.getBinariesDir().resolve(WP41C_M3PRE).toAbsolutePath().toString();
        } else {
            wp41cM3pre = WP41C_M3PRE;
        }

        return new SimpleCommandBuilder()
                .id(WP41C_M3PRE)
                .program(wp41cM3pre)
                .args(args1)
                .inputFiles(new InputFile(M1STATVARSFILENAME))
                .outputFiles(new OutputFile(M3NSAMCFILENAME))
                .timeout(config.getModule3Timeout())
                .build();
    }

    private Command createMatm3Cmd() {
        List<String> args1 = new ArrayList<>();
        args1.add("MOD1_${EXEC_NUM}.mat");
        args1.add("MOD2_${EXEC_NUM}.mat");
        args1.add(M3NSAMCFILENAME);
        args1.add("MOD3_${EXEC_NUM}.mat");
        args1.add(CommandConstants.EXECUTION_NUMBER_PATTERN);
        if (config.getRngSeed() != null) {
            args1.add(Integer.toString(config.getRngSeed()));
        } else {
            args1.add(Integer.toString(0));
        }

        String wp41cM3;
        if (config.getBinariesDir() != null) {
            wp41cM3 = config.getBinariesDir().resolve(WP41C_M3).toAbsolutePath().toString();
        } else {
            wp41cM3 = WP41C_M3;
        }

        return new SimpleCommandBuilder()
        .id(WP41C_M3)
        .program(wp41cM3)
        .args(args1)
        .inputFiles(new InputFile("MOD1_${EXEC_NUM}.mat"),
                    new InputFile("MOD2_${EXEC_NUM}.mat"),
                    new InputFile(M3NSAMCFILENAME))
        .outputFiles(new OutputFile("MOD3_${EXEC_NUM}.mat"))
        .timeout(config.getModule3Timeout())
        .build();

    }

    private Command createMatm3reduceCmd(int clustNums) {
        String wp41cM3Reduce;
        if (config.getBinariesDir() != null) {
            wp41cM3Reduce = config.getBinariesDir().resolve(WP41C_M3_REDUCE).toAbsolutePath().toString();
        } else {
            wp41cM3Reduce = WP41C_M3_REDUCE;
        }
        List<InputFile> m3partsfiles = new ArrayList<>(clustNums);
        for (int i = 0; i < clustNums; i++) {
            m3partsfiles.add(new InputFile("MOD3_" + i + ".mat"));
        }
        m3partsfiles.add(new InputFile(M1STATVARSFILENAME));
        return new SimpleCommandBuilder()
                .id(WP41C_M3_REDUCE)
                .program(wp41cM3Reduce)
                .args("./",
                      M1STATVARSFILENAME,
                      "" + clustNums,
                      M3OUTPUTFILENAME)
                .inputFiles(m3partsfiles)
                .outputFiles(new OutputFile(M3OUTPUTFILENAME))
                .timeout(config.getModule3Timeout())
                .build();
    }

    private double[][] computeModule3(int nSamples) throws Exception {
        LOGGER.info("Executing wp41 module3 (IR: {}, tflag: {}, number of clusters: {}), getting {} samples", config.getIr(), config.getTflag(), nClusters, nSamples);

        return computationManager.execute(new ExecutionEnvironment(createEnv(), WORKING_DIR_PREFIX, config.isDebug()), new AbstractExecutionHandler<double[][]>() {
            @Override
            public List<CommandExecution> before(Path workingDir) throws IOException {
                return Arrays.asList(new CommandExecution(createMatm3PreCmd(nClusters, nSamples), 1, priority),
                        new CommandExecution(createMatm3Cmd(), nClusters, priority),
                        new CommandExecution(createMatm3reduceCmd(nClusters), 1, priority)
                );
            }
            @Override
            public double[][] after(Path workingDir, ExecutionReport report) throws IOException {
                report.log();
                if (report.getErrors().isEmpty()) {
                    LOGGER.debug("Retrieving module3 results from file {}", M3OUTPUTFILENAME);
                    MatFileReader mfr = new MatFileReader();
                    Map<String, MLArray> content = mfr.read(workingDir.resolve(M3OUTPUTFILENAME).toFile());
                    String errMsg = Utils.MLCharToString((MLChar) content.get("errmsg"));
                    if (!("Ok".equalsIgnoreCase(errMsg))) {
                        throw new MatlabException(errMsg);
                    }
                    MLArray xNew = content.get("Y");
                    MLDouble mld = (MLDouble) xNew;
                    double[][] xNewMat = mld.getArray();

                    if (config.getValidationDir() != null) {
                        // store output file with the samples, for validation purposes
                        try {
                            Files.copy(workingDir.resolve(M3OUTPUTFILENAME), config.getValidationDir().resolve("MOD3_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId() + ".mat"));
                        } catch (Throwable t) {
                            LOGGER.error(t.getMessage(), t);
                        }
                    }
                    return xNewMat;
                } else {
                    return null;
                }
            }
        }).join();
    }

    private Command createBinSamplerCmd(Path iFilePath, int nSamples) {
        String wp41bIs;
        if (config.getBinariesDir() != null) {
            wp41bIs = config.getBinariesDir().resolve(WP41B_IS).toAbsolutePath().toString();
        } else {
            wp41bIs = WP41B_IS;
        }
        return new SimpleCommandBuilder()
                .id(WP41B_IS)
                .program(wp41bIs)
                .args(iFilePath.toAbsolutePath().toString(),
                      B1OUTPUTFILENAME,
                      "" + nSamples)
                .inputFiles(new InputFile(iFilePath.getFileName().toString()))
                .outputFiles(new OutputFile(B1OUTPUTFILENAME))
                .build();
    }

    public double[][] computeBinSampling(double[] marginalExpectations, int nSamples) throws Exception {
        Objects.requireNonNull(marginalExpectations);
        if (marginalExpectations.length == 0) {
            throw new IllegalArgumentException("empty marginalExpectations array");
        }
        if (nSamples <= 0) {
            throw new IllegalArgumentException("number of samples must be positive");
        }

        return computationManager.execute(new ExecutionEnvironment(createEnv(), WORKING_DIR_PREFIX, config.isDebug()), new AbstractExecutionHandler<double[][]>() {
            @Override
            public List<CommandExecution> before(Path workingDir) throws IOException {
                Utils.writeWP41BinaryIndependentSamplingInputFile(workingDir.resolve(B1INPUTFILENAME), marginalExpectations);

                LOGGER.info("binsampler, asking for {} samples", nSamples);

                Command cmd = createBinSamplerCmd(workingDir.resolve(B1INPUTFILENAME), nSamples);
                return Collections.singletonList(new CommandExecution(createBinSamplerCmd(workingDir.resolve(B1INPUTFILENAME), nSamples), 1, priority));
            }

            @Override
            public double[][] after(Path workingDir, ExecutionReport report) throws IOException {
                report.log();
                if (report.getErrors().isEmpty()) {
                    LOGGER.debug("Retrieving binsampler results from file {}", B1OUTPUTFILENAME);
                    MatFileReader mfr = new MatFileReader();
                    Map<String, MLArray> content;
                    content = mfr.read(workingDir.resolve(B1OUTPUTFILENAME).toFile());
                    String errMsg = Utils.MLCharToString((MLChar) content.get("errmsg"));
                    if (!("Ok".equalsIgnoreCase(errMsg))) {
                        throw new MatlabException(errMsg);
                    }
                    MLArray xNew = content.get("STATUS");
                    Objects.requireNonNull(xNew);
                    MLDouble mld = (MLDouble) xNew;
                    return mld.getArray();
                } else {
                    return null;
                }
            }
        }).join();
    }

}
