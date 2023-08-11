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

import org.opengauss.cmrestapi.OGCmdExecuter.CmdResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Title: CMRestAPI
 * @author: xuemengen
 * @Description:
 * Main entry.
 * (1) Initialization, acquisition and verification of information required by the program.
 * (2) Start the cluster information query server thread -- InfoQueryThread.
 * (3) Start the thread of monitoring database role change to primary -- Role2PrimaryMonitor.
 * Created on: 2022/09/07
 */
public class CMRestAPI {
    public final String APPLICATION_NAME = "CMRestAPI";
    public static String envFile;
    public static String dataPath;
    public static List<String> recvAddrList;
    public static String prefix = "/CMRestAPI/RecvAddrList/";
    public static String hostIp = null;
    public static String port = null;
    public static int nodeId;
    public static String peerIpPorts = null;
    public static OGCmdExecuter ogCmdExecuter = null;
    public static String appWhiteListFile = null;
    public static HashSet<String> appWhiteList = null;
    public static Long lastModified = 0l;
    private static Logger logger = LoggerFactory.getLogger(CMRestAPI.class);
    private static final String CHECK_GAUSSDB_PROCESS_CMD = 
            "ps ux | grep -v grep | grep \"bin/gaussdb -D \" | awk '{print $2}'";
    /**
     * @Title: main
     * @Description:
     * Main entry.
     * @param args
     *  -e enfFile
     *  [-w appWhiteListFile]
     */
    public static void main(String[] args) {
        parseAndCheckCmdLine(args);
        checkEnvfileAndDataPath();
        ogCmdExecuter = new OGCmdExecuter(envFile);
        if (!checkGaussdbRunning()) {
            logger.error("Gaussdb is not running, waiting for more than 30s, exit!");
            System.exit(ErrorCode.ESRCH.getCode());
        }
        getClusterStaticInfo();
        if (appWhiteListFile != null) {
            checkAppWhiteListFile();
            getAppWhiteList();
        }
        new Role2PrimaryMonitor().start();
        new InfoQueryThread().start();
    }
    
    private static void getClusterStaticInfo() {
        CmdResult cmdResult = ogCmdExecuter.cmctlViewNative();
        if (cmdResult == null) {
            logger.error("Failed to get static cluster info.");
            System.exit(ErrorCode.EUNKNOWN.getCode());
        }
        if (cmdResult.statusCode != 0 || "".equals(cmdResult.resultString)) {
            logger.error("Failed to get static cluster info.");
            System.exit(cmdResult.statusCode);
        }
        nodeId = Integer.parseInt(matchRegex("node:(\\d+)\\s+", cmdResult.resultString));
        dataPath = matchRegex("datanodeLocalDataPath :(.+)\\s+", cmdResult.resultString);
        hostIp = matchRegex("datanodeLocalHAIP 1:(.+)\\s+", cmdResult.resultString);
        port = matchRegex("datanodePort :(.*)\\s+", cmdResult.resultString);
        Pattern pattern = Pattern.compile("datanodePeer\\d+HAIP 1:(.+)\\s+datanodePeer\\d+HAPort :(.+)\\s+");
        Matcher matcher = pattern.matcher(cmdResult.resultString);
        if (peerIpPorts == null) {
            peerIpPorts = "";
        }
        boolean isFirst = true;
        while (matcher.find()) {
            String peerIp = matcher.group(1);
            String peerPortString = matcher.group(2);
            int peerPort = Integer.parseInt(peerPortString) - 1;
            if (isFirst) {
                isFirst = false;
            } else {
                peerIpPorts += ",";
            }
            peerIpPorts += peerIp + ":" + peerPort;
        }
    }
    
    private static void checkEnvfileAndDataPath() {
        File envFileFile = new File(envFile);
        if (!envFileFile.isFile()) {
            logger.error("{} is not a file!", envFile);
            System.exit(ErrorCode.EISDIR.getCode());
        }
        if (!envFileFile.exists()) {
            logger.error("{} is not exist!", envFile);
            System.exit(ErrorCode.ENOENT.getCode());
        }
        String cmd = "source " + envFile + "; gaussdb -V";
        CmdResult cmdResult = OGCmdExecuter.execCmd(cmd);
        if (cmdResult == null || cmdResult.statusCode != 0) {
            logger.error("env file {} is invalid!", envFile);
            System.exit(ErrorCode.EINVAL.getCode());
        }
    }
    
    private static void checkAppWhiteListFile() {
        File file = new File(appWhiteListFile);
        if (!file.exists()) {
            logger.error("{} is not exist!", appWhiteListFile);
            System.exit(ErrorCode.ENOENT.getCode());
        }
        if (!file.isFile()) {
            logger.error("{} is not a file!", appWhiteListFile);
            System.exit(ErrorCode.EISDIR.getCode());
        }
    }
    
    /**
     * @Title: appWhiteListFileModified
     * @Description:
     * check whether appWhiteListFile was modified.
     * @return
     * boolean
     */
    public static boolean appWhiteListFileModified() {
        if (new File(appWhiteListFile).lastModified() > lastModified) {
            return true;
        }
        return false;
    }
    
    /**
     * @Title: getAppWhiteList
     * @Description:
     * get appWhiteList from appWhiteListFile.
     * void
     */
    public static void getAppWhiteList() {
        if (appWhiteList == null) {
            appWhiteList = new HashSet<String>();
        } else {
            appWhiteList.clear();
        }
        try (BufferedReader br = new BufferedReader(new FileReader(appWhiteListFile))) {
            String line = null;
            while ((line = br.readLine()) != null) {
                appWhiteList.add(line);
            }
            br.close();
        } catch (IOException e) {
            appWhiteList = null;
            logger.error(e.getMessage());
        }
    }
    
    private static boolean checkGaussdbRunning() {
        boolean isRunning = false;
        for (int i = 0; i < 10; ++i) {
            CmdResult cmdResult = OGCmdExecuter.execCmd(CHECK_GAUSSDB_PROCESS_CMD);
            if (cmdResult != null && cmdResult.statusCode == 0 &&
                    !"".equals(cmdResult.resultString)) {
                isRunning = true;
                break;
            }
            logger.info("gaussdb is not running, waiting");
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
        return isRunning;
    }
    
    private static void parseAndCheckCmdLine(String[] args) {
        int argsLen = args.length;
        if (argsLen < 2) {
            System.out.println("-e envFile is needed!");
            System.exit(ErrorCode.EINVAL.getCode());
        }
        for (int i = 0; i < argsLen; ++i) {
            switch (args[i]) {
            case "-e":
                envFile = args[++i];
                break;
            case "-w":
                appWhiteListFile = args[++i];
                break;
            default:
                System.out.println("option " + args[i] + " is not support");
                System.exit(ErrorCode.EINVAL.getCode());
            }
        }
    }

    public static String matchRegex(String patternString, String targetString) {
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(targetString);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
