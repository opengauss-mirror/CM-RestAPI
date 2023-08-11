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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @Title: OGCmdExecuter
 * @author: xuemengen
 * @Description: 
 * openGauss command executer.
 * Created on: 2022/09/08
 */
public class OGCmdExecuter {
    private final String SOURCE_ENV_CMD;
    private static Logger logger = LoggerFactory.getLogger(OGCmdExecuter.class);

    public OGCmdExecuter(String envFile) {
        this.SOURCE_ENV_CMD = "source " + envFile + "; ";
    }

    /**
     * @Title: CmdResult
     * @author: xuemengen
     * @Description: Result of executing command.
     * Created on: 2022/09/08
     */
    static class CmdResult {
        int statusCode;
        String resultString;
        public CmdResult(int s, String res) {
            statusCode = s;
            resultString = res;
        }
    }

    /**
     * @Title: execCmd
     * @Description:
     * Execute shell command
     * @param command
     * @return
     * CmdResult
     */
    public static CmdResult execCmd(String command) {
        logger.debug("Excuting command: {}.",command);
        String[] cmd = new String[]{"/bin/sh", "-c", command};
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(cmd);
        } catch (IOException e) {
            e.printStackTrace();
            logger.error("Exception happend when excute shell command: {}.\nDetail:\n{}", command, e);
            return null;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuffer sb = new StringBuffer();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }
            int statusCode = process.waitFor();
            String resultString = sb.toString();
            logger.debug("Result:\nstatusCode: {}\nresultString: {}", statusCode, resultString);
            return new CmdResult(statusCode, resultString);
        } catch (IOException | InterruptedException exp) {
            logger.error("Exception happend when excute shell command: {}.\nDetail:\n{}", command, exp);
        }
        return null;
    }
    
    private String getCmctlCmd(String action, String options) {
        return "cm_ctl " + action + " " + options;
    }

    /**
     * @Title: gsctlQuery
     * @Description:
     * Execute gs_ctl query -D DATAPATH.
     * @return
     * CmdResult
     */
    public CmdResult gsctlQuery() {
        String cmd = SOURCE_ENV_CMD + "timeout 5 "
                + "gs_ctl query -D " + CMRestAPI.dataPath;
        return execCmd(cmd);
    }

    /**
     * @Title: cmctlQuery
     * @Description:
     * cm_ctl query 
     * @param options
     * @return
     * CmdResult
     */
    public CmdResult cmctlQuery(String options) {
        String cmd = SOURCE_ENV_CMD + "timeout 5 " + getCmctlCmd("query", options);
        return execCmd(cmd);
    }
    
    /**
     * @Title: cmctlViewNode
     * @Description:
     * Execute cm_ctl view -n nodeId
     * @param nodeId
     * @return
     * CmdResult
     */
    public CmdResult cmctlViewNode(String nodeId) {
        String options = "";
        if (nodeId == null || !"".equals(nodeId) ) {
            options = "-n " + nodeId;
        }
        String cmd = SOURCE_ENV_CMD + "timeout 5 " + getCmctlCmd("view", options);
        return execCmd(cmd);
    }
    
    /**
     * @Title: cmctlViewAll
     * @Description:
     * Execute cm_ctl view -n nodeId
     * @param nodeId
     * @return
     * CmdResult
     */
    public CmdResult cmctlViewAll() {
        String cmd = SOURCE_ENV_CMD + "timeout 5 " + getCmctlCmd("view", null);
        return execCmd(cmd);
    }
    
    /**
     * @Title: cmctlViewNative
     * @Description:
     * Execute cm_ctl view -N. Get static config info of current node.
     * @return
     * CmdResult
     */
    public CmdResult cmctlViewNative() {
        String cmd = SOURCE_ENV_CMD + "timeout 5 " + getCmctlCmd("view", "-N");
        return execCmd(cmd);
    }

    /**
     * @Title: cmctlDdb
     * @Description:
     * cm_ctl ddb DCC_CMD
     * DCC_CMD:
     *     [--prefix] --get key
     *     --put key value
     *  [--prefix] --delete key
     *  --cluster_info
     *  --leader_info
     * @param action
     * @param hasPrefix
     * @param key
     * @param value
     * @return
     * CmdResult
     */
    private CmdResult cmctlDdb(String action, boolean hasPrefix, String key, String value) {
        String cmd = SOURCE_ENV_CMD + "cm_ctl ddb ";
        if (hasPrefix) {
            cmd += "--prefix ";
        }
        cmd += action;
        if (!"--cluster_info".equals(action) && !"--leader_info".equals(action)) {
            cmd += " " + key;
            if ("--put".equals(action)) {
                cmd += " " + value;
            }
        }
        return execCmd(cmd);
    }

    /**
     * @Title: cmctlDdbPut
     * @Description:
     * cm_ctl ddb --put key value
     * @param key
     * @param value
     * @return
     * CmdResult
     */
    public CmdResult cmctlDdbPut(String key, String value) {
        return cmctlDdb("--put", false, key, value);
    }

    /**
     * @Title: cmctlDdbGet
     * @Description:
     * cm_ctl ddb [--prefix] --get
     * @param key
     * @param hasPrefix
     * @return
     * CmdResult
     */
    private CmdResult cmctlDdbGet(String key, Boolean hasPrefix) {
        return cmctlDdb("--get", hasPrefix, key, null);
    }

    /**
     * @Title: cmctlDdbGet
     * @Description:
     * cm_ctl ddb --get
     * @param key
     * @return
     * CmdResult
     */
    public CmdResult cmctlDdbGet(String key) {
        return cmctlDdbGet(key, false);
    }

    /**
     * @Title: cmctlDdbGetPrefix
     * @Description:
     * cm_ctl ddb --prefix --get
     * @param key
     * @return
     * CmdResult
     */
    public CmdResult cmctlDdbGetPrefix(String key) {
        return cmctlDdbGet(key, true);
    }

    /**
     * @Title: cmctlDdbDelete
     * @Description:
     * cm_ctl ddb --delete
     * @param key
     * @return
     * CmdResult
     */
    public CmdResult cmctlDdbDelete(String key) {
        return cmctlDdb("--delete", false, key, null);
    }

    /**
     * @Title: cmctlDdbDeletePrefix
     * @Description:
     * cm_ctl ddb --prefix --delete
     * @param key
     * @return
     * CmdResult
     */
    public CmdResult cmctlDdbDeletePrefix(String key) {
        return cmctlDdb("--delete", true, key, null);
    }
    
    /**
     * @Title: deleteRecvAddr
     * @Description:
     * Delete receive address
     * @param clientIp
     * @param app
     * @return
     * CmdResult
     */
    public CmdResult deleteRecvAddr(String clientIp, String app) {
        String key = CMRestAPI.prefix + clientIp;
        if (app == null || "".equals(app)) {
            return cmctlDdbDeletePrefix(key);
        }
        key += "/" + app;
        return cmctlDdbDelete(key);
    }
    
    /**
     * @Title: getRecvAddrList
     * @Description:
     * Get receive address list from dcc.
     * @return
     * Map<String,String>: [.., {"clientIp/app", recvAddr}, ..]
     */
    public Map<String, String> getRecvAddrList() {
        CmdResult cmdResult = cmctlDdbGetPrefix(CMRestAPI.prefix);
        if (cmdResult.statusCode != 0 || cmdResult.resultString.matches("Key not found")) {
            return null;
        }
        Map<String, String> clientIpRecvAddrMap = new HashMap<>();
        Pattern pattern = Pattern.compile(CMRestAPI.prefix + "(.*[\\s]+.*)[\\s]+?");
        Matcher matcher = pattern.matcher(cmdResult.resultString);
        while (matcher.find()) {
            String keyValue = matcher.group(1);
            String[] kv = keyValue.split("\\s+");
            clientIpRecvAddrMap.put(kv[0], kv[1]);
        }
        return clientIpRecvAddrMap;
    }

    /**
     * @Title: saveRecvAddr
     * @Description:
     * Save recvaddr to ddb
     * @param clientIp
     * @param app
     * @param recvAddr
     * @return
     * CmdResult
     */
    public CmdResult saveRecvAddr(String clientIp, String app, String recvAddr) {
        String key = CMRestAPI.prefix + clientIp;
        if (app != null && !"".equals(app)) {
            key += "/" + app;
        }
        return cmctlDdbPut(key, recvAddr);
    }

    /**
     * @Title: getClusterStatus
     * @Description:
     * Get cluster status by executing cm_ctl query -v.
     * @return
     * CmdResult
     */
    public CmdResult getClusterStatus() {
        return cmctlQuery("-v");
    }
    
    /**
     * @Title: getNodeStatus
     * @Description:
     * Get node status by executing cm_ctl query -v -i nodeId.
     * @param nodeId
     * @return
     * CmdResult
     */
    public CmdResult getNodeStatus(int nodeId) {
        return cmctlQuery("-v -n " + nodeId);
    }
}
