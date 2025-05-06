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

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @Title: InfoQueryThread
 * @author: xuemengen
 * @Description: InfoQueryThread
 * Start class of server.
 * Created on: 2022/09/08
 */
@SpringBootApplication
public class InfoQueryThread implements Runnable {
    private Thread thread;
    private final String THREAD_NAME;
    private Logger logger = LoggerFactory.getLogger(InfoQueryThread.class);
    
    public InfoQueryThread() {
        THREAD_NAME = "InfoQueryThread";
    }

    class ThreadException implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            logger.error("Error occured when web server start. \nDetail:{}" + e.getMessage());
            System.exit(ErrorCode.EUNKNOWN.getCode());
        }
    }

    public void start() {
        logger.info("Starting thread {}", THREAD_NAME);
        if (thread == null) {
            thread = new Thread(this, THREAD_NAME);
            thread.setUncaughtExceptionHandler(new ThreadException());
            thread.start ();
        }
    }

    @Override
    public void run() {
        SpringApplication.run(InfoQueryThread.class);
    }

    @PreDestroy
    public void preDestroy() {
        logger.info("Destroying CMRestAPI.");
    }
}
