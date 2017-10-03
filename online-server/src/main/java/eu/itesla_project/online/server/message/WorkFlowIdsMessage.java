/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package eu.itesla_project.online.server.message;

import java.util.Collection;

import javax.json.stream.JsonGenerator;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 *
 * @author Quinary <itesla@quinary.com>
 */
public class WorkFlowIdsMessage extends Message<Collection<String>> {

    private String type = "workflowIds";

    public WorkFlowIdsMessage(Collection<String> workflowIds) {
        super(workflowIds);

    }

    @Override
    protected String getType() {
        return type;
    }

    @Override
    public String toJson() {
        ObjectMapper json = new ObjectMapper();
        json.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        try {
            return json.writeValueAsString(this);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void toJson(JsonGenerator generator) {

    }

}
