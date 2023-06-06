package io.keyss.view_record_demo

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        println("1 or 1=${1 or 1}")
        println("1 or 0=${1 or 0}")
        println("0 or 0=${0 or 0}")
        println("1 and 0=${1 and 0}")
        println("1 and 1=${1 and 1}")
        println("0 and 0=${0 and 0}")

        println()
        println()

        println("-1 or -1=${-1 or -1}")
        println("-1 or 0=${-1 or 0}")
        println("0 or 0=${0 or 0}")
        println("-1 and 0=${-1 and 0}")
        println("-1 and -1=${-1 and -1}")
        println("0 and 0=${0 and 0}")
    }
}
