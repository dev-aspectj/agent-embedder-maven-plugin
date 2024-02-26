package dev.aspectj;

import org.springframework.stereotype.Component;

import static java.lang.reflect.Modifier.isFinal;

@Component
// Make class final to on purpose -> expect remove final agent to do its job and un-final it again
public final class FirstComponent {
  @LogWriteAccess
  private String field1;
  private Integer field2;
  private boolean field3;
  @LogWriteAccess
  private Double field4;

  static {
    if (isFinal(FirstComponent.class.getModifiers()))
      System.out.println("Remove final agent seems to be inactive, class FirstComponent should be non-final");
  }

  public void setField1(String field1) {
    this.field1 = field1;
  }

  public void setField2(Integer field2) {
    this.field2 = field2;
  }

  public void setField3(boolean field3) {
    this.field3 = field3;
  }

  public void setField4(Double field4) {
    this.field4 = field4;
  }
}
