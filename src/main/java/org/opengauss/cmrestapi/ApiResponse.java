/*
 * Copyright (c) 2021 Huawei Technologies Co.,Ltd.
 *
 * CM is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */
package org.opengauss.cmrestapi;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;

/**
 * @Title: ApiResponse
 * @Description: Unified API response format
 */
public class ApiResponse {
    private int state;
    private long timestamp;
    private Object msg;
    private String error;

    private static final Gson gson = new Gson();

    public ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public ApiResponse(int state, Object msg) {
        this.state = state;
        this.timestamp = System.currentTimeMillis();
        this.msg = msg;
    }

    public ApiResponse(int state, Object msg, String error) {
        this.state = state;
        this.timestamp = System.currentTimeMillis();
        this.msg = msg;
        this.error = error;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Object getMsg() {
        return msg;
    }

    public void setMsg(Object msg) {
        this.msg = msg;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    /**
     * Create success response
     */
    public static ApiResponse success(Object msg) {
        return new ApiResponse(0, msg);
    }

    /**
     * Create failure response
     */
    public static ApiResponse failure(int state, String error) {
        Map<String, String> emptyMsg = new HashMap<>();
        return new ApiResponse(state, emptyMsg, error);
    }

    /**
     * Create failure response with custom message
     */
    public static ApiResponse failure(int state, Object msg, String error) {
        return new ApiResponse(state, msg, error);
    }

    /**
     * Convert to JSON string
     */
    public String toJson() {
        return gson.toJson(this);
    }

    /**
     * Convert to JSON string with pretty printing
     */
    public String toJsonPretty() {
        return gson.toJson(this);
    }
}

