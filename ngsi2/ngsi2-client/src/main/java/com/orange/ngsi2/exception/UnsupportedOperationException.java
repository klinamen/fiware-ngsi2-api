/*
 * Copyright (C) 2016 Orange
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.orange.ngsi2.exception;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collection;

/**
 * Created by pascale on 09/02/2016.
 */
public class UnsupportedOperationException extends Exception {

    private String error = "501";

    private String description = null;

    public UnsupportedOperationException(String operationName) {
        super("");
        description = String.format("this operation '%s' is not implemented", operationName);
    }

    @Override
    public String getMessage() {
        return description;
    }

    public String getError() {
        return error;
    }

    public String getDescription() {
        return description;
    }
}

