package com.fly.forgotyet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class ForgotyetBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgotyetBackendApplication.class, args);
    }

}
