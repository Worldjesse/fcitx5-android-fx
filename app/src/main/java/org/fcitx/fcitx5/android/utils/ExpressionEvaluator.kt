/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import kotlin.math.abs
import kotlin.math.round

/**
 * Simple arithmetic expression evaluator used by the keyboard calculator.
 *
 * Supports: +, -, *, /, ^, parentheses, decimal numbers and unary +/-.
 */
object ExpressionEvaluator {

    private const val EPSILON = 1e-12

    /**
     * Extracts the trailing arithmetic expression region from [text].
     * Returns the raw substring (including any surrounding whitespace) so the
     * caller can delete exactly that many characters; [evaluate] trims it before
     * parsing. Returns null if no expression characters are found at the end.
     */
    fun extractExpression(text: String): String? {
        val allowed = "0123456789.+-*/^() "
        var i = text.length
        while (i > 0 && text[i - 1] in allowed) {
            i--
        }
        return text.substring(i).takeIf { it.isNotBlank() }
    }

    /**
     * Evaluates the given arithmetic [expression].
     * Returns a [Result] containing the numeric value or the failure.
     */
    fun evaluate(expression: String): Result<Double> = runCatching {
        val parser = Parser(expression.trim())
        val value = parser.parseExpression()
        if (parser.hasRemaining()) {
            throw IllegalArgumentException("Unexpected trailing characters")
        }
        if (value.isNaN() || value.isInfinite()) {
            throw ArithmeticException("Result is not finite")
        }
        value
    }

    /**
     * Formats the calculated [value] as a compact string.
     * Whole numbers are returned without a decimal point; others are trimmed
     * of insignificant trailing zeros.
     */
    fun formatResult(value: Double): String {
        val rounded = round(value)
        return if (abs(value - rounded) < EPSILON) {
            if (abs(rounded) <= Long.MAX_VALUE) {
                rounded.toLong().toString()
            } else {
                rounded.toString().removeSuffix(".0")
            }
        } else {
            String.format("%.12f", value).trimEnd('0').trimEnd('.')
        }
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun hasRemaining(): Boolean {
            skipWhitespace()
            return pos < input.length
        }

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> {
                        pos++
                        value += parseTerm()
                    }

                    '-' -> {
                        pos++
                        value -= parseTerm()
                    }

                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> {
                        pos++
                        value *= parseFactor()
                    }

                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Division by zero")
                        value /= divisor
                    }

                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            val value = parseUnary()
            return if (peek() == '^') {
                pos++
                // right-associative: 2^3^2 == 2^(3^2)
                Math.pow(value, parseFactor())
            } else {
                value
            }
        }

        private fun parseUnary(): Double {
            return when (peek()) {
                '+' -> {
                    pos++
                    parseUnary()
                }

                '-' -> {
                    pos++
                    -parseUnary()
                }

                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            return when (val c = peek()) {
                '(' -> {
                    pos++
                    val value = parseExpression()
                    if (peek() != ')') throw IllegalArgumentException("Missing closing parenthesis")
                    pos++
                    value
                }

                in '0'..'9', '.' -> parseNumber()
                '\u0000' -> throw IllegalArgumentException("Unexpected end of expression")
                else -> throw IllegalArgumentException("Unexpected character: $c")
            }
        }

        private fun parseNumber(): Double {
            skipWhitespace()
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                pos++
            }
            val numStr = input.substring(start, pos)
            return numStr.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid number: $numStr")
        }

        private fun peek(): Char {
            skipWhitespace()
            return input.getOrNull(pos) ?: '\u0000'
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) {
                pos++
            }
        }
    }
}
