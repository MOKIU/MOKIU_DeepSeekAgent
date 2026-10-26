package com.example.calculator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Calculator#add(int, int) 的单元测试。
 * 覆盖：正数、负数、零、正负混合、Integer 边界值。
 */
class CalculatorTest {

    private final Calculator calculator = new Calculator();

    @Test
    void addTwoPositiveNumbers() {
        assertEquals(5, calculator.add(2, 3));
    }

    @Test
    void addPositiveAndZero() {
        assertEquals(2, calculator.add(2, 0));
        assertEquals(7, calculator.add(0, 7));
    }

    @Test
    void addTwoZeros() {
        assertEquals(0, calculator.add(0, 0));
    }

    @Test
    void addNegativeNumbers() {
        assertEquals(-7, calculator.add(-3, -4));
    }

    @Test
    void addPositiveAndNegative() {
        assertEquals(0, calculator.add(-5, 5));
        assertEquals(2, calculator.add(5, -3));
    }

    @Test
    void addIntegerBoundaries() {
        assertEquals(Integer.MAX_VALUE, calculator.add(Integer.MAX_VALUE, 0));
        assertEquals(Integer.MIN_VALUE, calculator.add(Integer.MIN_VALUE, 0));
        assertEquals(-1, calculator.add(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }
}
