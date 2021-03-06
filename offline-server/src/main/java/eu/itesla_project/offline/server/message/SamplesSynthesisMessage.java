/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package eu.itesla_project.offline.server.message;

import eu.itesla_project.offline.SampleSynthesis;
import java.util.Collection;
import javax.json.stream.JsonGenerator;

/**
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class SamplesSynthesisMessage extends Message {

    private final Collection<SampleSynthesis> samples;

    private final String workflowId;

    public SamplesSynthesisMessage(Collection<SampleSynthesis> samples, String workflowId) {
        this.samples = samples;
        this.workflowId = workflowId;
    }

    @Override
    protected String getType() {
        return "samplesSynthesis";
    }

    @Override
    public void toJson(JsonGenerator generator) {
        generator.write("workflowId", workflowId);
        generator.writeStartArray("samplesSynthesis");
        for (SampleSynthesis sample : samples) {
            generator.writeStartObject()
                        .write("id", sample.getId())
                        .writeStartObject("lastTaskEvent")
                            .write("type", sample.getLastTaskEvent().getTaskType().name())
                            .write("status", sample.getLastTaskEvent().getTaskStatus().name());
            if (sample.getLastTaskEvent().getFailureReason() != null) {
                generator.write("failureReason", sample.getLastTaskEvent().getFailureReason());
            }
            generator.writeEnd()
                    .writeEnd();
        }
        generator.writeEnd();
    }

}
