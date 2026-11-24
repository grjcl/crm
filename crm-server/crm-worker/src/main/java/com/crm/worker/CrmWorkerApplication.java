package com.crm.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 采集与定时任务入口。本任务只建空骨架，不启动、不实现业务。
 */
@SpringBootApplication
public class CrmWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrmWorkerApplication.class, args);
    }
}
