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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;

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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import com.google.gson.Gson;

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
    @Autowired
    private ApplicationContext context;
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
     * @Title: StatusInfo
     * @Description: Status information with id and name
     */
    class StatusInfo {
        int id;
        String name;
        public StatusInfo(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * @Title: GrStatus
     * @Description: GR status response structure (flattened format)
     */
    class GrStatus {
        String node;
        String instance_status;
        String server_status;
        int local_instance_id;
        int master_id;
        String maintainMode;
        String node_role;
        public GrStatus(String node, String instance_status, String server_status,
                       int local_instance_id, int master_id,
                       String maintainMode, String node_role) {
            this.node = node;
            this.instance_status = instance_status;
            this.server_status = server_status;
            this.local_instance_id = local_instance_id;
            this.master_id = master_id;
            this.maintainMode = maintainMode;
            this.node_role = node_role;
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
     * @Title: validateRequestParameters
     * @Description:
     * Validate that request only contains allowed parameters.
     * @param request HttpServletRequest
     * @param allowedParams Set of allowed parameter names
     * @return ResponseEntity with error if unknown parameters found, null if valid
     */
    private ResponseEntity<String> validateRequestParameters(HttpServletRequest request, Set<String> allowedParams) {
        Map<String, String[]> allParams = request.getParameterMap();
        for (String paramName : allParams.keySet()) {
            if (!allowedParams.contains(paramName)) {
                String clientIp = getClientIp(request);
                logger.warn("Rejected request from {} with unknown parameter: {}", clientIp, paramName);
                return buildErrorResponse(400,
                    "Unknown parameter: " + paramName + ". Allowed parameters: " + String.join(", ", allowedParams),
                    HttpStatus.BAD_REQUEST);
            }
        }
        return null;
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
     * @Title: buildSuccessResponse
     * @Description: Build success response with unified format
     * @param msg Response message object
     * @return ResponseEntity with unified JSON format
     */
    private ResponseEntity<String> buildSuccessResponse(Object msg) {
        ApiResponse response = ApiResponse.success(msg);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(response.toJson());
    }

    /**
     * @Title: buildErrorResponse
     * @Description: Build error response with unified format
     * @param state Error state code (non-zero)
     * @param error Error message
     * @param httpStatus HTTP status code
     * @return ResponseEntity with unified JSON format
     */
    private ResponseEntity<String> buildErrorResponse(int state, String error, HttpStatus httpStatus) {
        ApiResponse response = ApiResponse.failure(state, error);
        return ResponseEntity
                .status(httpStatus)
                .body(response.toJson());
    }

    /**
     * @Title: buildErrorResponse
     * @Description: Build error response with unified format and custom message
     * @param state Error state code (non-zero)
     * @param msg Custom message object
     * @param error Error message
     * @param httpStatus HTTP status code
     * @return ResponseEntity with unified JSON format
     */
    private ResponseEntity<String> buildErrorResponse(int state, Object msg, String error, HttpStatus httpStatus) {
        ApiResponse response = ApiResponse.failure(state, msg, error);
        return ResponseEntity
                .status(httpStatus)
                .body(response.toJson());
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
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }
        CmdResult cmdResult = ogCmdExcuter.getClusterStatus();
        if (cmdResult == null) {
            return buildErrorResponse(500, "Exec query command failed!", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (cmdResult.statusCode != 0) {
            String errorMsg;
            if (cmdResult.statusCode == 124) {
                errorMsg = "Exec query command timeout!";
            } else {
                errorMsg = cmdResult.resultString != null ? cmdResult.resultString : "Unknown error";
            }
            return buildErrorResponse(cmdResult.statusCode, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
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

        logger.info("Cluster status retrieved successfully");
        return buildSuccessResponse(clusterStatus);
    }

    /**
     * @Title: getGRClusterStatus
     * @Description:
     * Get oGRecoredr cluster information.
     */
    @GetMapping("/GRClusterStatus")
    public ResponseEntity<String> getGRClusterStatus(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        logger.info("Received get GR cluster status request from {}", clientIp);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED + " client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        CmdResult cmdResult = ogCmdExcuter.cmctlQuery();
        if (cmdResult == null) {
            return buildErrorResponse(500, "failed to get GR cluster status!", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (cmdResult.statusCode != 0 || cmdResult.resultString == null || cmdResult.resultString.trim().isEmpty()) {
            logger.error("cm_ctl query -Cvipdw failed, statusCode: {}, output: {}", cmdResult.statusCode,
                    cmdResult.resultString);
            String errorMsg = cmdResult.resultString != null ? cmdResult.resultString : "cm_ctl query -Cvipdw failed";
            return buildErrorResponse(cmdResult.statusCode, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        GRClusterStatus grStatus = parseGRClusterStatus(cmdResult.resultString);
        if (grStatus == null) {
            return buildErrorResponse(500, "Failed to parse cm_ctl query -Cvipdw output",
                                     HttpStatus.INTERNAL_SERVER_ERROR);
        }

        logger.info("GR cluster status retrieved successfully");
        return buildSuccessResponse(grStatus);
    }

    /**
     * Parse the output of Cluster information.
     * @param output command output
     * @return GRClusterStatus
     */
    private GRClusterStatus parseGRClusterStatus(String output) {
        List<CmServerStateEntry> cmServerStates = new ArrayList<>();
        List<DefinedResourceStateEntry> definedResourceStates = new ArrayList<>();
        String clusterState = null;
        String redistributing = null;
        String balanced = null;
        String currentAz = null;
        String enableWalrecord = null;

        String[] lines = output.split("\\r?\\n");
        String currentSection = "";

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                currentSection = line.replace("[", "").replace("]", "").trim();
                continue;
            }
            if (line.startsWith("-")) {
                continue;
            }

            if ("CMServer State".equalsIgnoreCase(currentSection)) {
                if (line.startsWith("node")) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 6) {
                    cmServerStates.add(new CmServerStateEntry(
                            parts[0], parts[1], parts[2],
                            parts[3], parts[4], parts[5]));
                }
            } else if ("Defined Resource State".equalsIgnoreCase(currentSection)) {
                if (line.startsWith("node")) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 6) {
                    String state = parts[5];
                    if (parts.length >= 7) {
                        StringBuilder builder = new StringBuilder(state);
                        for (int i = 6; i < parts.length; i++) {
                            builder.append(" ").append(parts[i]);
                        }
                        state = builder.toString();
                    }
                    definedResourceStates.add(new DefinedResourceStateEntry(
                            parts[0], parts[1], parts[2],
                            parts[3], parts[4], state));
                }
            } else if ("Cluster State".equalsIgnoreCase(currentSection)) {
                String[] kv = line.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    switch (key) {
                        case "cluster_state":
                            clusterState = value;
                            break;
                        case "redistributing":
                            redistributing = value;
                            break;
                        case "balanced":
                            balanced = value;
                            break;
                        case "current_az":
                            currentAz = value;
                            break;
                        case "enable_walrecord":
                            enableWalrecord = value;
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        VipdwClusterSummary summary = new VipdwClusterSummary(
                clusterState, redistributing, balanced, currentAz, enableWalrecord);
        return new GRClusterStatus(cmServerStates, definedResourceStates, summary);
    }

    /**
     * @Title: getNodeStatus
     * @Description:
     * Receive get NodeStatus request. Return status of current node if nodeId is not provided.
     * @param request
     * @return
     * ResponseEntity<String>
     */
    @GetMapping("/oGNodeStatus")
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
            String cmServerState = CMRestAPI.matchRegex("type.*CMServer\\s+instance_state.*: (.*)\\s+",
                                                        cmdResult.resultString);
            String dnRole = CMRestAPI.matchRegex("type.*Datanode\\s+instance_state.*: (.*)\\s+",
                                                        cmdResult.resultString);
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
     * @Title: getGrNodeStatus
     * @Description:
     * Receive get GR NodeStatus request. Return status of current node if nodeId is not provided.
     * @param request
     * @return
     * ResponseEntity<String>
     */
    @GetMapping("/NodeStatus")
    ResponseEntity<String> getGrNodeStatus(HttpServletRequest request) {
        Set<String> allowedParams = new HashSet<>();
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        String clientIp = getClientIp(request);
        logger.info("Received get GR node status request from {}", clientIp);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        CmdResult cmdResult = ogCmdExcuter.getstatus();
        if (cmdResult == null) {
            return buildErrorResponse(500, "Exec query command failed!", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (cmdResult.statusCode != 0) {
            String errorMsg;
            if (cmdResult.statusCode == 124) {
                errorMsg = "Exec query command timeout!";
            } else {
                errorMsg = cmdResult.resultString != null ? cmdResult.resultString : "Unknown error";
            }
            return buildErrorResponse(cmdResult.statusCode, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        GrStatus grStatus = parseOGRecorderNodeStatus(cmdResult.resultString);
        if (grStatus == null) {
            return buildErrorResponse(500, "Failed to parse node status", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        logger.info("Node status retrieved successfully");
        return buildSuccessResponse(grStatus);
    }

    /**
     * @Title: parseNodeStatus
     * @Description:
     * Parse node status from cm_ctl query result string.
     * @param resultString The result string from cm_ctl query command
     * @return NodeStatus object, or null if resultString is empty
     */
    private NodeStatus parseNodeStatus(String resultString) {
        if (resultString == null || resultString.trim().isEmpty()) {
            return null;
        }
        String nodeIp = CMRestAPI.matchRegex("node_ip.*: (.*)\\s+", resultString);
        String cmServerState = CMRestAPI.matchRegex("type.*CMServer\\s+instance_state.*: (.*)\\s+", resultString);
        String dnRole = CMRestAPI.matchRegex("type.*Datanode\\s+instance_state.*: (.*)\\s+", resultString);
        String dnState = CMRestAPI.matchRegex("HA_state.*: (.*)\\s+", resultString);
        return new NodeStatus(nodeIp, cmServerState, dnRole, dnState);
    }

    private String parseConfigValue(String resultString) {
        if (resultString == null || resultString.trim().isEmpty()) {
            return null;
        }
        Pattern pattern = Pattern.compile("value\\s+is\\s+(.+)\\.", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(resultString);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * @Title: parseCmConfigValue
     * @Description:
     * Parse CM config value from grep output.
     * Expected format: "key = value" (may have multiple lines with duplicates)
     * @param resultString The result string from grep command
     * @param key The config key name
     * @return The config value, or null if parsing fails
     */
    private String parseCmConfigValue(String resultString, String key) {
        if (resultString == null || resultString.trim().isEmpty()) {
            return null;
        }

        try {
            // Split by lines and process each line
            String[] lines = resultString.split("\\r?\\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // Pattern to match "key = value" format
                // Handle cases like "log_min_messages = warning" or "key=value"
                Pattern pattern = Pattern.compile("^" + Pattern.quote(key) + "\\s*=\\s*(.+)$");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String value = matcher.group(1).trim();
                    // Return the first valid match (to avoid duplicates)
                    return value;
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing CM config value: {}", e.getMessage());
        }

        return null;
    }

    private String parseGrConfigValueByFileContent(String resultString, String key) {
        if (resultString == null || resultString.trim().isEmpty() || key == null || key.trim().isEmpty()) {
            return null;
        }

        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=\\s*([^#\\r\\n]+)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(resultString);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    /**
     * @Title: parseDataUsage
     * @Description:
     * Parse data usage output from grcmd datausage command.
     * Expected format: "Total: 1024.00 GB, Used: 0.00 GB, Available: 1024.00 GB, Usage: 0.00%"
     * @param resultString The result string from grcmd datausage command
     * @return Map containing total, used, available, usage fields, or null if parsing fails
     */
    private Map<String, String> parseDataUsage(String resultString) {
        if (resultString == null || resultString.trim().isEmpty()) {
            return null;
        }

        Map<String, String> result = new HashMap<>();
        try {
            // Pattern to match: "Total: 1024.00 GB, Used: 0.00 GB, Available: 1024.00 GB, Usage: 0.00%"
            Pattern totalPattern = Pattern.compile("Total:\\s+([\\d.]+)\\s+(\\w+)");
            Pattern usedPattern = Pattern.compile("Used:\\s+([\\d.]+)\\s+(\\w+)");
            Pattern availablePattern = Pattern.compile("Available:\\s+([\\d.]+)\\s+(\\w+)");
            Pattern usagePattern = Pattern.compile("Usage:\\s+([\\d.]+)%");

            Matcher totalMatcher = totalPattern.matcher(resultString);
            if (totalMatcher.find()) {
                result.put("total", totalMatcher.group(1) + " " + totalMatcher.group(2));
            }

            Matcher usedMatcher = usedPattern.matcher(resultString);
            if (usedMatcher.find()) {
                result.put("used", usedMatcher.group(1) + " " + usedMatcher.group(2));
            }

            Matcher availableMatcher = availablePattern.matcher(resultString);
            if (availableMatcher.find()) {
                result.put("available", availableMatcher.group(1) + " " + availableMatcher.group(2));
            }

            Matcher usageMatcher = usagePattern.matcher(resultString);
            if (usageMatcher.find()) {
                result.put("usage", usageMatcher.group(1) + "%");
            }

            // If we got at least one field, return the result
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            logger.error("Error parsing data usage output: {}", e.getMessage());
        }

        return null;
    }

    private List<GrPerformanceStat> parseGrPerformance(String resultString) {
        List<GrPerformanceStat> stats = new ArrayList<>();
        if (resultString == null || resultString.trim().isEmpty()) {
            return stats;
        }

        String[] lines = resultString.split("\\r?\\n");
        boolean inTable = false;
        boolean headerPassed = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!inTable && line.startsWith("|") && line.contains("event") && line.contains("count")) {
                inTable = true;
                headerPassed = false;
                continue;
            }
            if (!inTable) {
                continue;
            }
            if (line.startsWith("+")) {
                headerPassed = true;
                continue;
            }
            if (!headerPassed) {
                continue;
            }
            if (!line.startsWith("|")) {
                break;
            }
            String[] cols = line.split("\\|");
            if (cols.length < 6) {
                continue;
            }

            String event = cols[1].trim();
            String countStr = cols[2].trim();
            String totalWaitStr = cols[3].trim();
            String avgWaitStr = cols[4].trim();
            String maxSingleStr = cols[5].trim();

            try {
                long count = Long.parseLong(countStr);
                long totalWait = Long.parseLong(totalWaitStr);
                long avgWait = Long.parseLong(avgWaitStr);
                long maxSingle = Long.parseLong(maxSingleStr);
                stats.add(new GrPerformanceStat(event, count, totalWait, avgWait, maxSingle));
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse performance line: {}", line);
            }
        }

        return stats;
    }

    /**
     * @Title: parseOGRecorderNodeStatus
     * @Description:
     * Parse GR node status from grcmd getstatus result string.
     * @param resultString The result string from grcmd getstatus command
     * @return GrStatus object
     */
    private GrStatus parseOGRecorderNodeStatus(String resultString) {
        if (resultString == null || resultString.trim().isEmpty()) {
            // Return default error status
            return new GrStatus(
                CMRestAPI.hostIp != null ? CMRestAPI.hostIp : "unknown",
                "unknown", "UNKNOWN", 0, 0, "unknown", "unknown"
            );
        }
        String node = CMRestAPI.hostIp != null ? CMRestAPI.hostIp : "unknown";

        String instanceStateName = "unknown";
        Pattern instanceStatusPattern = Pattern.compile("is\\s+(\\w+)\\s+and");
        Matcher instanceStatusMatcher = instanceStatusPattern.matcher(resultString);
        if (instanceStatusMatcher.find()) {
            instanceStateName = instanceStatusMatcher.group(1);
        }

        String serverStateName = "UNKNOWN";
        Pattern serverStatusPattern = Pattern.compile("and\\s+(\\w+)");
        Matcher serverStatusMatcher = serverStatusPattern.matcher(resultString);
        if (serverStatusMatcher.find()) {
            serverStateName = serverStatusMatcher.group(1);
        }

        int localInstanceId = 0;
        String instanceIdStr = CMRestAPI.matchRegex("instance (\\d+)", resultString);
        if (instanceIdStr != null) {
            try {
                localInstanceId = Integer.parseInt(instanceIdStr);
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse instance_id: {}", instanceIdStr);
            }
        }

        int masterId = 0;
        String masterIdStr = CMRestAPI.matchRegex("Master id is (\\d+)", resultString);
        if (masterIdStr != null) {
            try {
                masterId = Integer.parseInt(masterIdStr);
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse master_id: {}", masterIdStr);
            }
        }

        String maintainMode = "false";
        String nodeRole = "primary";
        // Determine node role based on server status
        if ("READWRITE".equals(serverStateName) || "NORMAL".equals(serverStateName)) {
            nodeRole = "primary";
        } else if ("READONLY".equals(serverStateName)) {
            nodeRole = "standby";
        }

        // Determine instance health based on GR_MAINTAIN
        if (resultString.contains("GR_MAINTAIN is TRUE")) {
            maintainMode = "true";
        } else if (resultString.contains("GR_MAINTAIN is FALSE")) {
            maintainMode = "false";
        }

        return new GrStatus(node, instanceStateName, serverStateName, localInstanceId,
            masterId, maintainMode, nodeRole);
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
     * Stop REST API service
     * This endpoint does not accept any parameters (query parameters or request body)
     */
    @PostMapping("/stopRestApi")
    public ResponseEntity<String> stopRestApi(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        logger.info("Received stopRest request from {}.", clientIp);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        // Reject any query parameters - this endpoint accepts no parameters
        Set<String> allowedParams = new HashSet<>();
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        // Reject request body if present (optional additional check)
        // Note: getContentLength() returns -1 if length is unknown, which we allow
        try {
            int contentLength = request.getContentLength();
            if (contentLength > 0) {
                return buildErrorResponse(400, "This endpoint does not accept request body. " +
                    "Please send an empty POST request.",
                    HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            logger.warn("Error checking request content length: {}", e.getMessage());
        }

        logger.info("Received graceful shutdown request");

        new Thread(() -> {
            try {
                Thread.sleep(100);
                logger.info("Application shutdown");
                int exitCode = SpringApplication.exit(context, () -> 0);
                logger.info("Spring context closed with exit code: " + exitCode);

                System.exit(exitCode);
            } catch (Exception e) {
                logger.error("Shutdown error", e);
                System.exit(1);
            }
        }).start();

        Map<String, String> msg = new HashMap<>();
        msg.put("message", "Shutdown initiated successfully");
        logger.info("Shutdown initiated successfully");
        return buildSuccessResponse(msg);
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

    @GetMapping("/logFiles")
    ResponseEntity<String> getLogFiles(
            HttpServletRequest request,
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "maxResults", defaultValue = "100") int maxResults,
            @RequestParam(value = "sortBy", defaultValue = "path") String sortBy) {

        String clientIp = getClientIp(request);
        logger.info("Received get logFile request from {}, filter: {}, maxResults: {}, sortBy: {}",
                    clientIp, filter, maxResults, sortBy);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        allowedParams.add("filter");
        allowedParams.add("maxResults");
        allowedParams.add("sortBy");
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        if (maxResults <= 0) {
            return buildErrorResponse(400, "maxResults must be greater than 0", HttpStatus.BAD_REQUEST);
        }

        if (maxResults > 1000) {
            maxResults = 1000;
            logger.warn("maxResults too large, limited to 1000");
        }
        if (!sortBy.equals("time") && !sortBy.equals("size") && !sortBy.equals("path")) {
            return buildErrorResponse(400, "sortBy must be time, size or path", HttpStatus.BAD_REQUEST);
        }

        try {
            String LogPath = System.getenv("GR_HOME");
            if (LogPath == null || LogPath.trim().isEmpty()) {
                return buildErrorResponse(500, "GR_HOME environment variable is not set!",
                                        HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Path logDir = Paths.get(LogPath, "log");
            if (!Files.exists(logDir) || !Files.isDirectory(logDir)) {
                return buildErrorResponse(500, "Log directory does not exist: " + LogPath + "/log",
                                        HttpStatus.INTERNAL_SERVER_ERROR);
            }

            List<LogFileInfo> logFiles = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(logDir)) {
                java.util.Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path filePath = iterator.next();
                    if (Files.isRegularFile(filePath)) {
                        try {
                            String fileName = filePath.getFileName().toString();

                            if (filter != null && !filter.trim().isEmpty()) {
                                if (!fileName.toLowerCase().contains(filter.toLowerCase())) {
                                    continue;
                                }
                            }
                            LogFileInfo fileInfo = new LogFileInfo();
                            fileInfo.setFileName(fileName);
                            fileInfo.setFilePath(filePath.toString());
                            fileInfo.setFileSize(Files.size(filePath));
                            fileInfo.setLastModified(Files.getLastModifiedTime(filePath).toMillis());
                            fileInfo.setRelativePath(logDir.relativize(filePath).toString());
                            logFiles.add(fileInfo);
                        } catch (IOException e) {
                            logger.warn("Failed to get file info for: {}", filePath, e);
                        }
                    }
                }
            }

            switch (sortBy.toLowerCase()) {
                case "time":
                    logFiles.sort((f1, f2) -> Long.compare(f2.getLastModified(), f1.getLastModified()));
                    break;
                case "size":
                    logFiles.sort((f1, f2) -> Long.compare(f2.getFileSize(), f1.getFileSize()));
                    break;
                case "path":
                default:
                    logFiles.sort((f1, f2) -> f1.getRelativePath().compareToIgnoreCase(f2.getRelativePath()));
                    break;
            }

            int totalFiles = logFiles.size();
            if (logFiles.size() > maxResults) {
                logFiles = logFiles.subList(0, maxResults);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("files", logFiles);
            result.put("total", totalFiles);
            result.put("returned", logFiles.size());
            result.put("filter", filter != null ? filter : "");
            result.put("sortBy", sortBy);
            logger.info("Returned {}/{} log files (filter: '{}', sortBy: '{}', total: {})",
                    logFiles.size(), maxResults, filter, sortBy, totalFiles);
            return buildSuccessResponse(result);
        } catch (IOException e) {
            logger.error("Failed to read log directory: {}", e.getMessage());
            return buildErrorResponse(500, "Failed to read log directory: " + e.getMessage(),
                                    HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            logger.error("Unexpected error while getting log files: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/wormFiles")
    ResponseEntity<String> getWormFiles(
            HttpServletRequest request,
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "maxResults", defaultValue = "100") int maxResults,
            @RequestParam(value = "sortBy", defaultValue = "path") String sortBy) {
        
        String clientIp = getClientIp(request);
        logger.info("Received get wormFile request from {}, filter: {}, maxResults: {}, sortBy: {}",
                    clientIp, filter, maxResults, sortBy);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        allowedParams.add("filter");
        allowedParams.add("maxResults");
        allowedParams.add("sortBy");
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        if (maxResults <= 0) {
            return buildErrorResponse(400, "maxResults must be greater than 0", HttpStatus.BAD_REQUEST);
        }

        if (maxResults > 1000) {
            maxResults = 1000;
            logger.warn("maxResults too large, limited to 1000");
        }
        if (!sortBy.equals("time") && !sortBy.equals("size") && !sortBy.equals("path")
                && !sortBy.equals("name") && !sortBy.equals("none")) {
            return buildErrorResponse(400, "sortBy must be time, size, path, name or none", HttpStatus.BAD_REQUEST);
        }

        try {
            CmdResult setResult = ogCmdExcuter.getGrConfig("DATA_FILE_PATH");
            String path = parseConfigValue(setResult.resultString);
            if (path == null || path.trim().isEmpty()) {
                String grHome = System.getenv("GR_HOME");
                if (grHome == null || grHome.trim().isEmpty()) {
                    return buildErrorResponse(500, "GR_HOME is not set!", HttpStatus.INTERNAL_SERVER_ERROR);
                }
                String cfgPath = Paths.get(grHome, "cfg", "gr_inst.ini").toString();
                if (!Files.exists(Paths.get(cfgPath))) {
                    return buildErrorResponse(500, "gr_inst.ini does not exist: " + cfgPath,
                                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
                String cfgContent = Files.readString(Paths.get(cfgPath));
                logger.info("cfgContent: " + cfgContent);
                String value = parseGrConfigValueByFileContent(cfgContent, "DATA_FILE_PATH");
                if (value == null || value.trim().isEmpty()) {
                    return buildErrorResponse(500, "DATA_FILE_PATH variable is not set in gr_inst.ini!",
                                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
                path = value;
            }

            Path basePath = Paths.get(path);
            if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
                return buildErrorResponse(500, "DATA_FILE_PATH does not exist: " + path,
                                        HttpStatus.INTERNAL_SERVER_ERROR);
            }

            List<LogFileInfo> logFiles = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(basePath)) {
                java.util.Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path filePath = iterator.next();
                    if (filePath.equals(basePath)) {
                        continue;
                    }
                    if (Files.isRegularFile(filePath) || Files.isDirectory(filePath)) {
                        try {
                            String fileName = filePath.getFileName().toString();

                            if (filter != null && !filter.trim().isEmpty()) {
                                if (!fileName.toLowerCase().contains(filter.toLowerCase())) {
                                    continue;
                                }
                            }

                            LogFileInfo fileInfo = new LogFileInfo();
                            fileInfo.setFileName(fileName);
                            fileInfo.setFilePath(filePath.toString());
                            if (Files.isDirectory(filePath)) {
                                fileInfo.setFileSize(4096);
                            } else {
                                fileInfo.setFileSize(Files.size(filePath));
                            }
                            fileInfo.setLastModified(Files.getLastModifiedTime(filePath).toMillis());
                            try {
                                Path relativePath = basePath.relativize(filePath);
                                fileInfo.setRelativePath(relativePath.toString());
                            } catch (IllegalArgumentException e) {
                                fileInfo.setRelativePath(fileName);
                            }

                            try {
                                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(filePath);
                                fileInfo.setFilePermissions(PosixFilePermissions.toString(permissions));
                            } catch (UnsupportedOperationException | IOException e) {
                                try {
                                    Object perm = Files.getAttribute(filePath, "unix:mode");
                                    if (perm != null) {
                                        int mode = (Integer) perm;
                                        fileInfo.setFilePermissions(String.format("%04o", mode & 0777));
                                    } else {
                                        fileInfo.setFilePermissions("unknown");
                                    }
                                } catch (Exception ex) {
                                    fileInfo.setFilePermissions("unknown");
                                }
                            }

                            logFiles.add(fileInfo);
                        } catch (IOException e) {
                            logger.warn("Failed to get file info for: {}", filePath, e);
                        }
                    }
                }
            }

            switch (sortBy.toLowerCase()) {
                case "time":
                    logFiles.sort((f1, f2) -> Long.compare(f2.getLastModified(), f1.getLastModified()));
                    break;
                case "size":
                    logFiles.sort((f1, f2) -> Long.compare(f2.getFileSize(), f1.getFileSize()));
                    break;
                case "path":
                default:
                    logFiles.sort((f1, f2) -> f1.getRelativePath().compareToIgnoreCase(f2.getRelativePath()));
                    break;
            }

            int totalFiles = logFiles.size();
            if (logFiles.size() > maxResults) {
                logFiles = logFiles.subList(0, maxResults);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("files", logFiles);
            result.put("total", totalFiles);
            result.put("returned", logFiles.size());
            result.put("filter", filter != null ? filter : "");
            result.put("sortBy", sortBy);
            logger.info("Returned {}/{} worm files (filter: '{}', sortBy: '{}', total: {})", 
                    logFiles.size(), maxResults, filter, sortBy, totalFiles);

            return buildSuccessResponse(result);
        } catch (IOException e) {
            logger.error("Failed to read worm file directory: {}", e.getMessage());
            return buildErrorResponse(500, "Failed to read worm file directory: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            logger.error("Unexpected error while getting worm files: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private DirectoryStats calculateDirectoryStats(Path directory, Path basePath) throws IOException {
        DirectoryStats stats = new DirectoryStats();
        stats.setDirectoryName(directory.getFileName().toString());
        stats.setDirectoryPath(directory.toString());
        stats.setRelativePath(basePath.relativize(directory).toString());

        long fileCount = 0;
        long totalSize = 0;

        try (java.util.stream.Stream<Path> fileStream = Files.walk(directory)) {
            java.util.Iterator<Path> fileIterator = fileStream.iterator();
            while (fileIterator.hasNext()) {
                Path filePath = fileIterator.next();
                if (Files.isRegularFile(filePath)) {
                    fileCount++;
                    totalSize += Files.size(filePath);
                }
            }
        }

        stats.setFileCount(fileCount);
        stats.setTotalSize(totalSize);

        return stats;
    }

    @GetMapping("/wormStatus")
    ResponseEntity<String> getWormStatus(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        logger.info("Received get worm status request from {}", clientIp);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        try {
            CmdResult setResult = ogCmdExcuter.getGrConfig("DATA_FILE_PATH");
            String path = parseConfigValue(setResult.resultString);
            if (path == null || path.trim().isEmpty()) {
                String grHome = System.getenv("GR_HOME");
                if (grHome == null || grHome.trim().isEmpty()) {
                    return buildErrorResponse(500, "GR_HOME is not set!", HttpStatus.INTERNAL_SERVER_ERROR);
                }
                String cfgPath = Paths.get(grHome, "cfg", "gr_inst.ini").toString();
                if (!Files.exists(Paths.get(cfgPath))) {
                    return buildErrorResponse(500, "gr_inst.ini does not exist: " + cfgPath,
                                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
                String cfgContent = Files.readString(Paths.get(cfgPath));
                logger.info("cfgContent: " + cfgContent);
                String value = parseGrConfigValueByFileContent(cfgContent, "DATA_FILE_PATH");
                if (value == null || value.trim().isEmpty()) {
                    return buildErrorResponse(500, "DATA_FILE_PATH variable is not set in gr_inst.ini!",
                                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
                path = value;
            }

            Path logDir = Paths.get(path);
            if (!Files.exists(logDir) || !Files.isDirectory(logDir)) {
                return buildErrorResponse(500, "DATA_FILE_PATH directory does not exist: " + path,
                                        HttpStatus.INTERNAL_SERVER_ERROR);
            }

            LogStats totalStats = new LogStats();
            totalStats.setDirectoryName("TOTAL");
            totalStats.setDirectoryPath(path);

            List<DirectoryStats> directoryStatsList = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.list(logDir)) {
                java.util.Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path subPath = iterator.next();
                    if (Files.isDirectory(subPath)) {
                        DirectoryStats dirStats = calculateDirectoryStats(subPath, logDir);
                        directoryStatsList.add(dirStats);
                        totalStats.setFileCount(totalStats.getFileCount() + dirStats.getFileCount());
                        totalStats.setTotalSize(totalStats.getTotalSize() + dirStats.getTotalSize());
                    }
                }
            }

            directoryStatsList.sort((d1, d2) -> Long.compare(d2.getFileCount(), d1.getFileCount()));

            LogStatsResponse response = new LogStatsResponse();
            response.setTotalStats(totalStats);
            response.setDirectoryStats(directoryStatsList);
            response.setTotalDirectories(directoryStatsList.size());

            logger.info("Returned statistics for {} directories, total files: {}, total size: {}",
                    directoryStatsList.size(), totalStats.getFileCount(), totalStats.getTotalSize());
            return buildSuccessResponse(response);
        } catch (IOException e) {
            logger.error("Failed to read directory for statistics: {}", e.getMessage());
            return buildErrorResponse(500, "Failed to read directory for statistics: " + e.getMessage(),
                                    HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            logger.error("Unexpected error while getting worm statistics: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(),
                                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/log")
    ResponseEntity<String> getLogContent(
            HttpServletRequest request,
            @RequestParam(value = "file") String file,
            @RequestParam(value = "offset", defaultValue = "1", required = true) long offset,
            @RequestParam(value = "count", defaultValue = "100", required = true) int count) {
        String clientIp = getClientIp(request);
        logger.info("Received get log content request from {}, file: {}, offset: {}, count: {}",
                clientIp, file, offset, count);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        allowedParams.add("file");
        allowedParams.add("offset");
        allowedParams.add("count");
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        if (file == null || file.trim().isEmpty()) {
            return buildErrorResponse(400, "file is required", HttpStatus.BAD_REQUEST);
        }
        if (offset <= 0) {
            return buildErrorResponse(400, "offset must be greater than 0", HttpStatus.BAD_REQUEST);
        }
        if (count <= 0) {
            return buildErrorResponse(400, "count must be greater than 0", HttpStatus.BAD_REQUEST);
        }
        if (count > 1000) {
            logger.warn("count too large ({}), limited to 1000", count);
            count = 1000;
        }

        try {
            String grHome = System.getenv("GR_HOME");

            if ((grHome == null || grHome.trim().isEmpty()) && CMRestAPI.envFile != null) {
                String cmd = "source " + CMRestAPI.envFile + "; echo $GR_HOME";
                CmdResult cmdResult = OGCmdExecuter.execCmd(cmd);
                if (cmdResult != null && cmdResult.statusCode == 0
                        && cmdResult.resultString != null && !cmdResult.resultString.trim().isEmpty()) {
                    grHome = cmdResult.resultString.trim();
                    logger.info("Resolved GR_HOME from envFile: {}", grHome);
                }
            }

            if (grHome == null || grHome.trim().isEmpty()) {
                return buildErrorResponse(500, "GR_HOME is not set in environment or envFile!",
                                        HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Path logDir = Paths.get(grHome, "log");
            if (!Files.exists(logDir) || !Files.isDirectory(logDir)) {
                return buildErrorResponse(500, "Log directory does not exist: " + logDir.toString(),
                                        HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Path requestedPath = Paths.get(file);
            if (requestedPath.isAbsolute()) {
                logger.warn("Absolute path is not allowed for log file: {}", requestedPath);
                return buildErrorResponse(400, "file must be a relative path under $GR_HOME/log",
                                        HttpStatus.BAD_REQUEST);
            }

            requestedPath = logDir.resolve(requestedPath).normalize();

            if (!requestedPath.startsWith(logDir.normalize())) {
                logger.warn("Attempt to access file outside GR_HOME/log: {}", requestedPath);
                return buildErrorResponse(403, "Access to the specified file is not allowed",
                                        HttpStatus.FORBIDDEN);
            }

            if (!Files.exists(requestedPath) || !Files.isRegularFile(requestedPath)) {
                return buildErrorResponse(404, "Log file not found: " + requestedPath.toString(),
                                        HttpStatus.NOT_FOUND);
            }

            List<String> lines = new ArrayList<>();
            boolean hasMore = false;
            long currentLine = 1;
            long totalLines = 0;

            try (BufferedReader br = Files.newBufferedReader(requestedPath, StandardCharsets.UTF_8)) {
                String line;

                while (currentLine < offset && (line = br.readLine()) != null) {
                    currentLine++;
                }
                if (currentLine < offset) {
                    totalLines = currentLine;
                    return buildErrorResponse(400,
                        String.format("offset (%d) exceeds total lines (%d) in file", offset, totalLines),
                        HttpStatus.BAD_REQUEST);
                }

                int readCount = 0;
                while (readCount < count && (line = br.readLine()) != null) {
                    lines.add(line);
                    readCount++;
                }

                if ((line = br.readLine()) != null) {
                    hasMore = true;
                }

                if (readCount < count) {
                    logger.info("Requested {} lines but only {} lines available (offset: {}, offset+count exceeds total lines)",
                        count, readCount, offset);
                }
            }

            LogContentResponse response = new LogContentResponse();
            response.setFileName(requestedPath.getFileName().toString());
            response.setFilePath(requestedPath.toString());
            response.setRelativePath(logDir.relativize(requestedPath).toString());
            response.setStartLine(offset);
            response.setRequestedLines(count);
            response.setReturnedLines(lines.size());
            response.setHasMore(hasMore);
            response.setLines(lines);

            logger.info("Returned {} lines from file {}, offset: {}, count: {}, hasMore: {}",
                    lines.size(), response.getRelativePath(), offset, count, hasMore);

            return buildSuccessResponse(response);
        } catch (IOException e) {
            logger.error("Failed to read log file {}: {}", file, e.getMessage());
            return buildErrorResponse(500, "Failed to read log file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            logger.error("Unexpected error while getting log content for file {}: {}", file, e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/performance")
    ResponseEntity<String> performance(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        logger.info("Received get gr performance request from {}", clientIp);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        try {
            CmdResult cmdResult = ogCmdExcuter.performance();
            if (cmdResult == null || cmdResult.statusCode != 0) {
                String errorMsg = "Failed to get performance stats: "
                        + (cmdResult != null ? cmdResult.resultString : "null");
                logger.error(errorMsg);
                return buildErrorResponse(500, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            List<GrPerformanceStat> stats = parseGrPerformance(cmdResult.resultString);
            logger.info("Returned {} performance stats", stats.size());
            return buildSuccessResponse(stats);
        } catch (Exception e) {
            logger.error("Unexpected error while getting performance stats: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/setCmCfg")
    ResponseEntity<String> setCmConf(
            HttpServletRequest request,
            @RequestParam(value = "mode", required = true) String mode,
            @RequestParam(value = "name", required = true) String name,
            @RequestParam(value = "value", required = true) String value) {
        String clientIp = getClientIp(request);
        logger.info("Received set cm config request from {}, mode: {}, name: {}, value: {}",
                clientIp, mode, name, value);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        allowedParams.add("mode");
        allowedParams.add("name");
        allowedParams.add("value");
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        if (name == null || name.trim().isEmpty()) {
            return buildErrorResponse(400, "name is required", HttpStatus.BAD_REQUEST);
        }

        if ((mode == null || mode.trim().isEmpty()) || (!mode.equals("agent") && !mode.equals("server"))) {
            return buildErrorResponse(400, "invalid mode", HttpStatus.BAD_REQUEST);
        }

        // Trim name and value to avoid leading/trailing spaces
        String trimmedName = name.trim();
        String trimmedValue = (value != null) ? value.trim() : "";

        // Validate parameter name format (only alphanumeric and underscore, cannot start/end with underscore)
        if (!trimmedName.matches("^[a-zA-Z0-9][a-zA-Z0-9_]*[a-zA-Z0-9]$|^[a-zA-Z0-9]$")) {
            return buildErrorResponse(400, "Invalid parameter name format. Parameter name can only contain letters, numbers, and underscores, and cannot start or end with underscore", HttpStatus.BAD_REQUEST);
        }

        try {
            CmdResult setResult = ogCmdExcuter.setCmConfig(mode, trimmedName, trimmedValue);
            if (setResult.statusCode != 0) {
                String errorMsg = "Failed to set cm config: " + setResult.resultString;
                logger.error(errorMsg);
                return buildErrorResponse(500, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Map<String, String> result = new HashMap<>();
            result.put("message", setResult.resultString);
            logger.info("Successfully set cm config: mode={}, name={}", mode, name);
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while setting cm config: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/reloadCmCfg")
    ResponseEntity<String> reloadCmConf(
            HttpServletRequest request,
            @RequestParam(value = "mode") String mode) {
        String clientIp = getClientIp(request);
        logger.info("Received reload cm config request from {}, mode: {}", clientIp, mode);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        allowedParams.add("mode");
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        if ((mode == null || mode.trim().isEmpty()) || (!mode.equals("agent") && !mode.equals("server"))) {
            return buildErrorResponse(400, "invalid mode, must be 'agent' or 'server'", HttpStatus.BAD_REQUEST);
        }

        try {
            CmdResult reloadResult = ogCmdExcuter.reloadCmConfig(mode);
            if (reloadResult.statusCode != 0) {
                String errorMsg = "Failed to reload cm config: " + reloadResult.resultString;
                logger.error(errorMsg);
                return buildErrorResponse(500, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Map<String, String> result = new HashMap<>();
            result.put("message", reloadResult.resultString);
            logger.info("Successfully reloaded cm config: mode={}", mode);
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while reloading cm config: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/setGrCfg")
    ResponseEntity<String> setGrConf(
            HttpServletRequest request,
            @RequestParam(value = "name", required = true) String name,
            @RequestParam(value = "value", required = true) String value) {
        String clientIp = getClientIp(request);
        logger.info("Received set oGRecorder config request from {}, name: {}, value: {}",
                clientIp, name, value);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        allowedParams.add("name");
        allowedParams.add("value");
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        if (name == null || name.trim().isEmpty()) {
            return buildErrorResponse(400, "name is NULL or empty", HttpStatus.BAD_REQUEST);
        }

        try {
            CmdResult setResult = ogCmdExcuter.setGrConfig(name, value);
            if (setResult.statusCode != 0) {
                String errorMsg = "Failed to set gr config: " + setResult.resultString;
                logger.error(errorMsg);
                return buildErrorResponse(500, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Map<String, String> result = new HashMap<>();
            result.put("message", setResult.resultString);
            logger.info("Successfully set gr config: name={}", name);
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while setting gr config: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/getCmCfg")
    ResponseEntity<String> getCmConf(
            HttpServletRequest request,
            @RequestParam(value = "mode", required = true) String mode,
            @RequestParam(value = "name", required = true) String name) {
        String clientIp = getClientIp(request);
        logger.info("Received get cm config request from {}, mode: {}, name: {}", clientIp, mode, name);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        allowedParams.add("mode");
        allowedParams.add("name");
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        if (name == null || name.trim().isEmpty()) {
            return buildErrorResponse(400, "name is required", HttpStatus.BAD_REQUEST);
        }

        if ((mode == null || mode.trim().isEmpty()) || (!mode.equals("agent") && !mode.equals("server"))) {
            return buildErrorResponse(400, "invalid mode", HttpStatus.BAD_REQUEST);
        }

        try {
            CmdResult cmdResult = ogCmdExcuter.getCmConfig(mode, name);
            if (cmdResult.statusCode != 0) {
                String errorMsg = "Failed to get cm config: " + cmdResult.resultString;
                logger.error(errorMsg);
                return buildErrorResponse(500, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            String value = parseCmConfigValue(cmdResult.resultString, name);
            Map<String, String> result = new HashMap<>();
            result.put("name", name);
            if (value != null) {
                result.put("value", value);
            } else {
                result.put("value", cmdResult.resultString.trim());
            }
            logger.info("Successfully got cm config: mode={}, name={}, value={}", mode, name, result.get("value"));
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while getting cm config: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/getGrCfg")
    ResponseEntity<String> getGrConf(
            HttpServletRequest request,
            @RequestParam(value = "name", required = true) String name) {
        String clientIp = getClientIp(request);
        logger.info("Received get oGRecorder config request from {}, name: {}", clientIp, name);

        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        allowedParams.add("name");
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        if (name == null || name.trim().isEmpty()) {
            return buildErrorResponse(400, "name is required", HttpStatus.BAD_REQUEST);
        }

        try {
            CmdResult cmdResult = ogCmdExcuter.getGrConfig(name);
            if (cmdResult.statusCode != 0) {
                String errorMsg = "Failed to get gr config: " + cmdResult.resultString;
                logger.error(errorMsg);
                return buildErrorResponse(500, errorMsg, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            String value = parseConfigValue(cmdResult.resultString);
            Map<String, String> result = new HashMap<>();
            result.put("name", name);
            if (value != null) {
                result.put("value", value);
            } else {
                result.put("value", cmdResult.resultString);
            }
            logger.info("Successfully got gr config: name={}, value={}", name, result.get("value"));
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while getting gr config: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/stop")
    ResponseEntity<String> stopImpl(
            HttpServletRequest request,
            @RequestParam(value = "node", required = false, defaultValue = "") String node) {
        String clientIp = getClientIp(request);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Map<String, String[]> allParams = request.getParameterMap();
        for (String paramName : allParams.keySet()) {
            if (!"node".equals(paramName)) {
                logger.warn("Rejected stop request from {} with unknown parameter: {}", clientIp, paramName);
                return buildErrorResponse(400, 
                    "Unknown parameter: " + paramName + ". Only node parameter is allowed.",
                    HttpStatus.BAD_REQUEST);
            }
        }

        try {
            CmdResult cmdResult;
            if (node.trim().isEmpty()) {
                logger.info("Received stop cluster request from {}", clientIp);
                cmdResult = ogCmdExcuter.stopCluster();
            } else {
                logger.info("Received stop node request from {}, node: {}", clientIp, node);
                cmdResult = ogCmdExcuter.stopNode(node);
            }

            if (cmdResult == null) {
                return buildErrorResponse(500, "Exec stop command failed!", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            if (cmdResult.statusCode != 0) {
                logger.error("Stop command failed: {}", cmdResult.resultString);
                return buildErrorResponse(500, cmdResult.resultString, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Map<String, String> result = new HashMap<>();
            result.put("message", cmdResult.resultString);
            logger.info("Successfully stopped: {}", cmdResult.resultString);
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while stopping: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/start")
    ResponseEntity<String> startImpl(
            HttpServletRequest request,
            @RequestParam(value = "node", required = false, defaultValue = "") String node) {
        String clientIp = getClientIp(request);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Map<String, String[]> allParams = request.getParameterMap();
        for (String paramName : allParams.keySet()) {
            if (!"node".equals(paramName)) {
                logger.warn("Rejected start request from {} with unknown parameter: {}", clientIp, paramName);
                return buildErrorResponse(400,
                    "Unknown parameter: " + paramName + ". Only node parameter is allowed.",
                    HttpStatus.BAD_REQUEST);
            }
        }

        try {
            CmdResult cmdResult;
            if (node.trim().isEmpty()) {
                logger.info("Received start cluster request from {}", clientIp);
                cmdResult = ogCmdExcuter.startCluster();
            } else {
                logger.info("Received start node request from {}, node: {}", clientIp, node);
                cmdResult = ogCmdExcuter.startNode(node);
            }

            if (cmdResult == null) {
                return buildErrorResponse(500, "Exec start command failed!", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            if (cmdResult.statusCode != 0) {
                logger.error("Start command failed: {}", cmdResult.resultString);
                return buildErrorResponse(500, cmdResult.resultString, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Map<String, String> result = new HashMap<>();
            result.put("message", cmdResult.resultString);
            logger.info("Successfully started: {}", cmdResult.resultString);
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while starting: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/switchover")
    ResponseEntity<String> switchoverImpl(
            HttpServletRequest request,
            @RequestParam(value = "node", required = true) String node) {
        String clientIp = getClientIp(request);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Map<String, String[]> allParams = request.getParameterMap();
        for (String paramName : allParams.keySet()) {
            if (!"node".equals(paramName)) {
                logger.warn("Rejected switchover request from {} with unknown parameter: {}", clientIp, paramName);
                return buildErrorResponse(400,
                    "Unknown parameter: " + paramName + ". Only 'node' parameter is allowed.",
                    HttpStatus.BAD_REQUEST);
            }
        }

        if (node.trim().isEmpty()) {
            return buildErrorResponse(400, "Node parameter is required", HttpStatus.BAD_REQUEST);
        }

        try {
            logger.info("Received switchover request from {}, node {}", clientIp, node);
            CmdResult cmdResult = ogCmdExcuter.switchover(node);

            if (cmdResult == null) {
                return buildErrorResponse(500, "Exec switchover command failed!", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            if (cmdResult.statusCode != 0) {
                logger.error("Switchover command failed: {}", cmdResult.resultString);
                return buildErrorResponse(500, cmdResult.resultString, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Map<String, String> result = new HashMap<>();
            result.put("message", cmdResult.resultString);
            logger.info("Successfully switchover: {}", cmdResult.resultString);
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while switchover: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/dataUsage")
    ResponseEntity<String> usageImpl(
            HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (!checkAppWhiteList(clientIp)) {
            logger.error(HttpStatus.UNAUTHORIZED.toString() + "client " + clientIp);
            return buildErrorResponse(401, "Unauthorized access", HttpStatus.UNAUTHORIZED);
        }

        Set<String> allowedParams = new HashSet<>();
        ResponseEntity<String> validationError = validateRequestParameters(request, allowedParams);
        if (validationError != null) {
            return validationError;
        }

        try {
            logger.info("Received usage request from {}", clientIp);
            CmdResult cmdResult = ogCmdExcuter.datausage();

            if (cmdResult == null) {
                return buildErrorResponse(500, "Exec usage command failed!", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            if (cmdResult.statusCode != 0) {
                logger.error("Usage command failed: {}", cmdResult.resultString);
                return buildErrorResponse(500, cmdResult.resultString, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            Map<String, String> result = parseDataUsage(cmdResult.resultString);
            if (result == null) {
                logger.error("Failed to parse data usage output: {}", cmdResult.resultString);
                return buildErrorResponse(500, "Failed to parse data usage output", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            logger.info("Successfully got usage info");
            return buildSuccessResponse(result);
        } catch (Exception e) {
            logger.error("Unexpected error while getting usage: {}", e.getMessage());
            return buildErrorResponse(500, "Unexpected error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

class LogFileInfo {
    private String fileName;
    private String filePath;
    private String relativePath;
    private long fileSize;
    private long lastModified;
    private String filePermissions;

    public LogFileInfo() {}

    public LogFileInfo(String fileName, String filePath, String relativePath, long fileSize, long lastModified) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.relativePath = relativePath;
        this.fileSize = fileSize;
        this.lastModified = lastModified;
    }

    public String getFileName() {
        return fileName;
    }
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getRelativePath() {
        return relativePath;
    }
    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public long getFileSize() {
        return fileSize;
    }
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public long getLastModified() {
        return lastModified;
    }
    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public String getFilePermissions() {
        return filePermissions;
    }
    public void setFilePermissions(String filePermissions) {
        this.filePermissions = filePermissions;
    }
}

class DirectoryStats {
    private String directoryName;
    private String directoryPath;
    private String relativePath;
    private long fileCount;
    private long totalSize;

    public DirectoryStats() {}

    public String getDirectoryName() {
        return directoryName;
    }
    public void setDirectoryName(String directoryName) {
        this.directoryName = directoryName;
    }

    public String getDirectoryPath() {
        return directoryPath;
    }
    public void setDirectoryPath(String directoryPath) {
        this.directoryPath = directoryPath;
    }

    public String getRelativePath() {
        return relativePath;
    }
    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public long getFileCount() {
        return fileCount;
    }
    public void setFileCount(long fileCount) {
        this.fileCount = fileCount;
    }
    public long getTotalSize() {
        return totalSize;
    }
    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public String getFormattedSize() {
        return formatFileSize(totalSize);
    }

    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}

class CfgInfo {
    String status;
    String name;
    String value;
    String mode;
    public CfgInfo(String status, String name, String value, String mode) {
        this.status = status;
        this.name = name;
        this.value = value;
        this.mode = mode;
    }
}

class LogStats extends DirectoryStats {
    private int totalDirectories;

    public LogStats() {}
    public int getTotalDirectories() {
        return totalDirectories;
    }
    public void setTotalDirectories(int totalDirectories) {
        this.totalDirectories = totalDirectories;
    }
}

class LogStatsResponse {
    private LogStats totalStats;
    private List<DirectoryStats> directoryStats;
    private int totalDirectories;

    public LogStatsResponse() {}

    public LogStats getTotalStats() {
        return totalStats;
    }
    public void setTotalStats(LogStats totalStats) {
        this.totalStats = totalStats;
    }

    public List<DirectoryStats> getDirectoryStats() {
        return directoryStats;
    }
    public void setDirectoryStats(List<DirectoryStats> directoryStats) {
        this.directoryStats = directoryStats;
    }

    public int getTotalDirectories() {
        return totalDirectories;
    }
    public void setTotalDirectories(int totalDirectories) {
        this.totalDirectories = totalDirectories;
    }
}

class LogContentResponse {
    private String fileName;
    private String filePath;
    private String relativePath;
    private long startLine;
    private int requestedLines;
    private int returnedLines;
    private boolean hasMore;
    private List<String> lines;

    public LogContentResponse() {}

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public long getStartLine() {
        return startLine;
    }

    public void setStartLine(long startLine) {
        this.startLine = startLine;
    }

    public int getRequestedLines() {
        return requestedLines;
    }

    public void setRequestedLines(int requestedLines) {
        this.requestedLines = requestedLines;
    }

    public int getReturnedLines() {
        return returnedLines;
    }

    public void setReturnedLines(int returnedLines) {
        this.returnedLines = returnedLines;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public List<String> getLines() {
        return lines;
    }

    public void setLines(List<String> lines) {
        this.lines = lines;
    }
}

class CmServerStateEntry {
    String node;
    String nodeName;
    String nodeIp;
    String instanceId;
    String dataPath;
    String state;

    public CmServerStateEntry(String node, String nodeName, String nodeIp,
            String instanceId, String dataPath, String state) {
        this.node = node;
        this.nodeName = nodeName;
        this.nodeIp = nodeIp;
        this.instanceId = instanceId;
        this.dataPath = dataPath;
        this.state = state;
    }
}

class DefinedResourceStateEntry {
    String node;
    String nodeName;
    String nodeIp;
    String resName;
    String instance;
    String state;

    public DefinedResourceStateEntry(String node, String nodeName, String nodeIp,
            String resName, String instance, String state) {
        this.node = node;
        this.nodeName = nodeName;
        this.nodeIp = nodeIp;
        this.resName = resName;
        this.instance = instance;
        this.state = state;
    }
}

class VipdwClusterSummary {
    String clusterState;
    String redistributing;
    String balanced;
    String currentAz;
    String enableWalrecord;

    public VipdwClusterSummary(String clusterState, String redistributing,
            String balanced, String currentAz, String enableWalrecord) {
        this.clusterState = clusterState;
        this.redistributing = redistributing;
        this.balanced = balanced;
        this.currentAz = currentAz;
        this.enableWalrecord = enableWalrecord;
    }
}

class GRClusterStatus {
    List<CmServerStateEntry> cmServerStates;
    List<DefinedResourceStateEntry> definedResourceStates;
    VipdwClusterSummary clusterSummary;

    public GRClusterStatus(List<CmServerStateEntry> cmServerStates,
            List<DefinedResourceStateEntry> definedResourceStates,
            VipdwClusterSummary clusterSummary) {
        this.cmServerStates = cmServerStates;
        this.definedResourceStates = definedResourceStates;
        this.clusterSummary = clusterSummary;
    }
}

class GrPerformanceStat {
    String event;
    long count;
    long total_wait_time;
    long avg_wait_time;
    long max_single_time;

    public GrPerformanceStat(String event, long count, long total_wait_time,
            long avg_wait_time, long max_single_time) {
        this.event = event;
        this.count = count;
        this.total_wait_time = total_wait_time;
        this.avg_wait_time = avg_wait_time;
        this.max_single_time = max_single_time;
    }
}