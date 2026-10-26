package com.example.calculator;

/**
 * 测试辅助类：与 tmp2/com/example/calculator/Calculator.java 内容一致。
 * 放在 src/test 而非 src/main，保证主源码树零改动；
 * Maven 测试编译（test-compile）会自动编译本类并加入测试 classpath，
 * 使 CalculatorTest 可通过标准 mvn test 流程运行。
 */
public class Calculator {

    public int add(int a, int b) {
        return a + b;
    }
}
