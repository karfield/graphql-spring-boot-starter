package com.karfield.graphql;

import com.karfield.graphql.annotations.EnableGraphQL;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableGraphQL()
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
