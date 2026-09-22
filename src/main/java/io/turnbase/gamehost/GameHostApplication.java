package io.turnbase.gamehost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GameHostApplication {

  public static void main(String[] args) {
    SpringApplication.run(GameHostApplication.class, args);
  }
}
