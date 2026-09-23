package com.blackstore

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BlackStoreApplication

fun main(args: Array<String>) {
    runApplication<BlackStoreApplication>(*args)
}
