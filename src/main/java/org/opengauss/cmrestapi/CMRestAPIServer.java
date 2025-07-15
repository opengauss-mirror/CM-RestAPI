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

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.google.gson.Gson;
import java.io.File;
import java.net.Inet4Address;
import java.net.UnknownHostException;

import org.opengauss.cmrestapi.OGCmdExecuter.CmdResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * @Title: CMRestAPIServer
 * @author: xuemengen
 * @Description:
 * Server for listening request from application, manager platform etc.
 * Created on: 2022/09/07
 */
@RestController
@RequestMapping("/CMRestAPI")
public class CMRestAPIServer {
    private final String UNKNOWN = "unknown";
    private final String LOCALHOST = "127.0.0.1";
    private final String LOCALHOST_IPV6 = "0:0:0:0:0:0:0:1";
    private final String SEPARATOR = ",";
    private Logger logger = LoggerFactory.getLogger(CMRestAPIServer.class);
    private OGCmdExecuter ogCmdExcuter = new OGCmdExecuter(CMRestAPI.envFile);

    private static final String[] IP_HEADER_CANDIDATES = {
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED",
            "HTTP_VIA",
            "REMOTE_ADDR"
    };

    /**
     * @Title: NodeStatus
     * @author: xuemengen
     * @Description:
     * Database node status.
     * Created on: 2022/09/07
     */
    class NodeStatus {
        String nodeIp;
        String cmServerState;
        String dnRole;
        String dnState;
        public NodeStatus(String nodeIp, String cmServerState, String dnRole, String dnState) {
            this.nodeIp = nodeIp;
            this.cmServerState = cmServerState;
            this.dnRole = dnRole;
            this.dnState = dnState;
        }
    }

    /**
     * @Title: DefResStatus
     * @author: xuemengen
     * @Description: Defined Resource State
     * Created on: 2022/09/19
     */
    class DefResStatus {
        int nodeId;
        String resName;
        String state;
        public DefResStatus(int nodeId, String state, String resName) {
             this.nodeId = nodeId;
             this.state = state;
             this.resName = resName;
        }
    }

    /**
     * @Title: ClusterStatus
     * @author: xuemengen
     * @Description:
     * Cluster status.
     * Created on: 2022/09/07
     */
    class ClusterStatus {
        String clusterState;
        List<NodeStatus> nodesStatus;
        List<DefResStatus> defResStatus;
    }

    private String getClientIp(HttpServletRequest request) {
        String ipAddress = null;
        /* 
         * In the multi-proxy scenario, the header is extracted to
         * obtain the IP address list, and the first IP address is taken.
         */
        for (String header : IP_HEADER_CANDIDATES) {
            String ipList = request.getHeader(header);
            if (ipList == null || ipList.length() == 0 || UNKNOWN.equalsIgnoreCase(ipList)) {
                continue;
            }
            ipAddress = ipList.split(SEPARATOR)[0];
        }
        
        /* 
         * The getRemoteAddr method is used to obtain
         * the IP address without a proxy or SLB.
         */
        if (ipAddress == null || ipAddress.length() == 0 || UNKNOWN.equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }

        /* 
         * If the local IP address is used, the local IP
         * address is configured based on the NIC.
         */
        if (LOCALHOST.equals(ipAddress) || LOCALHOST_IPV6.equals(ipAddress)) {
            try {
                ipAddress = Inet4Address.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                ipAddress = "localhost";
                logger.error("Error when get localhost ip.\nDetail:", e.getMessage());
            }
        }
        return ipAddress;
    }

    /**
     * @Title: checkAppWhiteList
     * @Description:
     * Check whether client ip is in appWhiteList.
     * Notice: if the appWhiteListFile does not exist, the verification
     * is not required, then return true.
     * @param request
     * @return
     * boolean
     */
    private boolean checkAppWhiteList(String clientIp) {
        if (CMRestAPI.appWhiteListFile == null || !new File(CMRestAPI.appWhiteListFile).exists()) {
            return true;
        }
        if (CMRestAPI.appWhiteListFileModified()) {
            CMRestAPI.getAppWhiteList();
        }
        if (CMRestAPI.appWhiteList == null) {
            return false;
        }
        return CMRestAPI.appWhiteList.contains(clientIp);
    }

    /**
     * @Title: getClusterStatus
     * @Description:
     * Receive get ClusterStatus request.
     * @param request
     * @return
     * ResponseEntity<String>
     */
    @GetMapping("/ClusterStatus")
    public ResponseEntity<String> getClusterStatus(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        logger.info("Received get cluster status request from {}", clientIp);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(HttpStatus.UNAUTHORIZED.toString());
        }
        CmdResult cmdResult = ogCmdExcuter.getClusterStatus();
        if (cmdResult == null) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"msg\": \"Exec query command failed!\"}");
        }
        if (cmdResult.statusCode != 0) {
            String msg = null;
            if (cmdResult.statusCode == 124) {
                msg = "{\"msg\": \"Exec query command timeout!\"}";
            } else {
                msg = cmdResult.resultString;
            }
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(msg);
        }
        ClusterStatus clusterStatus = new ClusterStatus();
        String[] nodesStatus = cmdResult.resultString.split("\\s+-{70,}\\s+");
        int startPos = 0;
        if (nodesStatus[0].contains("Defined Resource State")) {
            startPos = 1;
            // get defined resource state
            clusterStatus.defResStatus = new ArrayList<DefResStatus>();
            String defResStatusString = nodesStatus[0].split("\\s+-+\\s+")[1];
            String[] resStateList = defResStatusString.split("\\r?\\n");
            for (String resState : resStateList) {
                if (!resState.trim().isEmpty()) {
                    String[] items = resState.split("\\s+");
                    int nodeId = Integer.parseInt(items[0]);
                    String resName = items[2];
                    String state = items[4];
                    clusterStatus.defResStatus.add(new DefResStatus(nodeId, state, resName));
                }
            }
        } else {
            clusterStatus.defResStatus = null;
        }
        String clusterState = CMRestAPI.matchRegex("cluster_state.*: (.*)\\s+", nodesStatus[startPos]);
        clusterStatus.clusterState = clusterState;
        clusterStatus.nodesStatus = new ArrayList<NodeStatus>();
        for(int i = startPos + 1; i < nodesStatus.length; ++i) {
            if (nodesStatus[i] != null && !nodesStatus[i].trim().isEmpty()) {
                String nodeIp = CMRestAPI.matchRegex("node_ip.*: (.*)\\s+", nodesStatus[i]);
                String cmServerState = CMRestAPI.matchRegex("type.*CMServer\\s+instance_state.*: (.*)\\s+", nodesStatus[i]);
                String dnRole = CMRestAPI.matchRegex("type.*Datanode\\s+instance_state.*: (.*)\\s+", nodesStatus[i]);
                String dnState = CMRestAPI.matchRegex("HA_state.*: (.*)\\s+", nodesStatus[i]);
                clusterStatus.nodesStatus.add(new NodeStatus(nodeIp, cmServerState, dnRole, dnState));
            }
        }
        
        Gson clusterGson = new Gson();
        String result = clusterGson.toJson(clusterStatus);
        logger.info(result);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(result);
    }

    /**
     * @Title: getNodeStatus
     * @Description:
     * Receive get NodeStatus request. Return status of current node if nodeId is not provided.
     * @param request
     * @return
     * ResponseEntity<String>
     */
    @GetMapping("/NodeStatus")
    ResponseEntity<String> getNodeStatus(HttpServletRequest request,
            @RequestParam(value="nodeId", required = false, defaultValue = "0")int nodeId) {
        String clientIp = getClientIp(request);
        logger.info("Received get node status request from {}", clientIp);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(HttpStatus.UNAUTHORIZED.toString());
        }
        if (nodeId == 0) {
            nodeId = CMRestAPI.nodeId;
        }
        CmdResult cmdResult = ogCmdExcuter.getNodeStatus(nodeId);
        if (cmdResult == null) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"msg\": \"Exec query command failed!\"}");
        }
        if (cmdResult.statusCode != 0) {
            String msg = null;
            if (cmdResult.statusCode == 124) {
                msg = "{\"msg\": \"Exec query command timeout!\"}";
            } else {
                msg = cmdResult.resultString;
            }
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(msg);
        }
        NodeStatus nodeStatus = null;
        if (cmdResult.resultString != null && !cmdResult.resultString.trim().isEmpty()) {
            String nodeIp = CMRestAPI.matchRegex("node_ip.*: (.*)\\s+", cmdResult.resultString);
            String cmServerState = CMRestAPI.matchRegex("type.*CMServer\\s+instance_state.*: (.*)\\s+", cmdResult.resultString);
            String dnRole = CMRestAPI.matchRegex("type.*Datanode\\s+instance_state.*: (.*)\\s+", cmdResult.resultString);
            String dnState = CMRestAPI.matchRegex("HA_state.*: (.*)\\s+", cmdResult.resultString);
            nodeStatus = new NodeStatus(nodeIp, cmServerState, dnRole, dnState);
        }
        
        Gson clusterGson = new Gson();
        String result = clusterGson.toJson(nodeStatus);
        logger.info(result);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(result);
    }

    /**
     * @Title: registerOrUpdateRecvAddr
     * @Description:
     * If key does not exist, register the address of receiving master info, else update.
     * key = prefix("/CMRestAPI/RecvAddrList/") + clientIp + "/" + appName.
     * value = url
     * @param request
     * @param url
     * @param app
     * @return
     * ResponseEntity<String>
     */
    @PutMapping("/RecvAddr")
    public ResponseEntity<String> registerOrUpdateRecvAddr(HttpServletRequest request, @RequestParam(value = "url")String url,
            @RequestParam(value = "app", required = false, defaultValue = "")String app) {
        String clientIp = getClientIp(request);
        logger.info("Received put recvaddr request from {}:{}.", clientIp, app);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(HttpStatus.UNAUTHORIZED.toString());
        }
        CmdResult cmdResult = ogCmdExcuter.saveRecvAddr(clientIp, app, url);
        if (cmdResult == null) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"msg\": \"Exec put command failed!\"}");
        }
        if (cmdResult.statusCode != 0) {
            String msg = null;
            if (cmdResult.statusCode == 124) {
                msg = "{\"msg\": \"Exec put command timeout!\"}";
            } else {
                msg = cmdResult.resultString;
            }
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(msg);
        }
        return ResponseEntity
                .status(HttpStatus.OK)
                .body("Register receive address successfully.");
    }

    /**
     * @Title: deleteRegisterAddr
     * @Description:
     * Delete register address.
     * key = prefix("/CMRestAPI/RecvAddrList/") + clientIp + "/" + appName.
     * @param request
     * @param app
     * @return
     * ResponseEntity<String>
     */
    @DeleteMapping("/RecvAddr")
    public ResponseEntity<String> deleteRegisterAddr(HttpServletRequest request,
            @RequestParam(value = "app", required = false, defaultValue = "")String app) {
        String clientIp = getClientIp(request);
        logger.info("Received delete RecvAddr request from {}.", clientIp);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(HttpStatus.UNAUTHORIZED.toString());
        }
        CmdResult cmdResult = ogCmdExcuter.deleteRecvAddr(clientIp, app);
        if (cmdResult == null) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"msg\": \"Exec delete command failed!\"}");
        }
        if (cmdResult.statusCode != 0) {
            String msg = null;
            if (cmdResult.statusCode == 124) {
                msg = "{\"msg\": \"Exec delete command timeout!\"}";
            } else {
                msg = cmdResult.resultString;
            }
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(msg);
        }
        return ResponseEntity
                .status(HttpStatus.OK)
                .body("Deleted successfully.");
    }
}