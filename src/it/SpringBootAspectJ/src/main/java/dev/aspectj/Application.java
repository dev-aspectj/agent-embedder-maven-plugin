package dev.aspectj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class Application {
  public static void main(String[] args) {
    try (ConfigurableApplicationContext context = SpringApplication.run(Application.class, args)) {
      FirstComponent firstComponent = context.getBean(FirstComponent.class);
      firstComponent.setField1("one");
      firstComponent.setField2(2);
      firstComponent.setField3(true);
      firstComponent.setField4(4.44D);

      SecondComponent secondComponent = context.getBean(SecondComponent.class);
      secondComponent.setField1("one");
      secondComponent.setField2(2);
      secondComponent.setField3(true);
      secondComponent.setField4(4.44D);
    }
  }
}
