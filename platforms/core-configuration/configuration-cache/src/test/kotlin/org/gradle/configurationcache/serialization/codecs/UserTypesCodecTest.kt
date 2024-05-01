/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache.serialization.codecs

import org.gradle.configurationcache.extensions.useToRun
import org.gradle.configurationcache.problems.PropertyTrace
import org.gradle.configurationcache.serialization.ReadContext
import org.gradle.configurationcache.serialization.beans.Optimizable
import org.gradle.configurationcache.serialization.readNonNull
import org.gradle.configurationcache.serialization.skipTag
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import java.io.File
import java.io.FileWriter
import java.io.Writer
import kotlin.math.ceil
import kotlin.math.log
import kotlin.random.Random

enum class FieldType {
    Int, String, Bean
}

typealias BeanSchema = List<FieldType>

class UserTypesCodecTest : AbstractUserTypeCodecTest() {

    class Foo(var i: Int, var s: String, var bar: Bar) : Optimizable {
        override suspend fun ReadContext.readBean() {
            val _bar = readNonNull<Bar>()
            skipTag()
            val _i = readInt()
            skipTag()
            val _s = readString()
            i = _i
            s = _s
            bar = _bar
        }
    }

    data class Bar(val i: Int)

    fun Random.generateSchema(): BeanSchema {
        val maxLength = 100
        val fieldsCount = nextInt(2, maxLength)
        return List(fieldsCount) {
            FieldType.values().random(this)
        }
    }

    fun Writer.writeType(schema: BeanSchema) {
        val name = schema.name
        appendLine(
            """
            class $name(${fieldsOf(schema)}) : Optimizable {

                override suspend fun ReadContext.readBean() {"""
        )

        schema.forEachIndexed { index, fieldType ->
            when (fieldType) {
                FieldType.Int -> appendLine("skipTag();${schema.fieldNameFor(index)}=readInt()")
                FieldType.String -> appendLine("skipTag();${schema.fieldNameFor(index)}=readString()")
                FieldType.Bean -> appendLine("${schema.fieldNameFor(index)}=readNonNull()")
            }
        }
        appendLine(
            """
                }
            }"""
        )
    }

    fun Writer.writeTypeUnoptimized(schema: BeanSchema) {
        val name = schema.name
        write(
            """
            class Unoptimized$name(${fieldsOf(schema)})
        """
        )
    }

    private fun fieldsOf(schema: BeanSchema): String {
        return schema.mapIndexed { index, fieldType ->
            when (fieldType) {
                FieldType.Int -> "var ${schema.fieldNameFor(index)}: Int = $index"
                FieldType.String -> "var ${schema.fieldNameFor(index)}: String = \"$index\""
                FieldType.Bean -> "var ${schema.fieldNameFor(index)}: Bean = Bean($index)"
            }
        }.joinToString()
    }

    private fun BeanSchema.getPadLength(): Int = ceil(log(size.toDouble(), 10.0)).toInt()

    private fun BeanSchema.fieldNameFor(index: Int): String =
        "_" + "$index".padStart(getPadLength(), '0')

    private val BeanSchema.name: String
        get() = joinToString("") {
            when (it) {
                FieldType.Int -> "I"
                FieldType.String -> "S"
                FieldType.Bean -> "B"
            }
        }

    @Test
    fun `generate`() {
        val file = File("/Users/sopivalov/Projects/wm24hack/build.gradle.kts").apply {
            parentFile.mkdirs()
        }
        FileWriter(file).useToRun {
            generateBenchmarkProject()
        }
    }

    private fun Writer.generateBenchmarkProject(): Appendable {
        appendLine(
            """
                    import ${Optimizable::class.qualifiedName}
                    import ${ReadContext::class.qualifiedName}
                    import org.gradle.configurationcache.serialization.readNonNull
                    import org.gradle.configurationcache.serialization.skipTag

                    data class Bean(val i : Int)

                    abstract class BeanTask : DefaultTask() {
                        @get:Input
                        abstract val bean: Property<Any>

                        @TaskAction
                        fun doSomething() {}
                    }

                """.trimIndent()
        )

        val schemaNames = mutableSetOf<String>()
        List(10) {
            Random.generateSchema()
        }.toSet().forEach {
            val schema = it
            val taskName = schema.name + "Task"
            schemaNames += schema.name

            writeType(schema)
            writeTypeUnoptimized(schema)
            appendLine(
                """
                        tasks.register<BeanTask>("$taskName") {
                            bean.set(${schema.name}())
                        }

                        tasks.register<BeanTask>("Unoptimized$taskName") {
                            bean.set(Unoptimized${schema.name}())
                        }
                    """.trimIndent()
            )
        }

        return appendLine("""
                    tasks.register("optimized") {
                        ${
            schemaNames.joinToString("\n") {
                "dependsOn(\"${it}Task\")"
            }
        }
                    }

                    tasks.register("unoptimized") {
                        ${
            schemaNames.joinToString("\n") {
                "dependsOn(\"Unoptimized${it}Task\")"
            }
        }
                    }

                """.trimIndent())
    }

    @Test
    fun `can handle optimizable bean`() {
        val foo = Foo(42, "foo", Bar(43))

        val read = configurationCacheRoundtripOf(foo)

        assertThat(
            read.bar,
            equalTo(foo.bar)
        )

        assertThat(
            read.i,
            equalTo(foo.i)
        )

        assertThat(
            read.s,
            equalTo(foo.s)
        )
    }


    @Test
    fun `can handle deeply nested graphs`() {

        val deepGraph = Peano.fromInt(1024)

        val read = configurationCacheRoundtripOf(deepGraph)

        assertThat(
            read.toInt(),
            equalTo(deepGraph.toInt())
        )
    }

    @Test
    fun `internal types codec leaves not implemented trace for unsupported types`() {

        val unsupportedBean = 42 to "42"
        val problems = serializationProblemsOf(unsupportedBean, codecs().internalTypesCodec())
        val problem = problems.single()
        assertInstanceOf<PropertyTrace.Gradle>(
            problem.trace
        )
        assertThat(
            problem.message.toString(),
            equalTo("objects of type 'kotlin.Pair' are not yet supported with the configuration cache.")
        )
    }

    @Test
    fun `can handle anonymous enum subtypes`() {
        EnumSuperType.values().forEach {
            assertThat(
                configurationCacheRoundtripOf(it),
                sameInstance(it)
            )
        }
    }

    enum class EnumSuperType {

        SubType1 {
            override fun displayName() = "one"
        },
        SubType2 {
            override fun displayName() = "two"
        };

        abstract fun displayName(): String
    }

    @Test
    fun `preserves identity of java util logging Level`() {
        configurationCacheRoundtripOf(java.util.logging.Level.INFO to java.util.logging.Level.WARNING).run {
            assertThat(
                first,
                sameInstance(java.util.logging.Level.INFO)
            )
            assertThat(
                second,
                sameInstance(java.util.logging.Level.WARNING)
            )
        }
    }

    @Test
    fun `Peano sanity check`() {

        assertThat(
            Peano.fromInt(0),
            equalTo(Peano.Z)
        )

        assertThat(
            Peano.fromInt(1024).toInt(),
            equalTo(1024)
        )
    }

    sealed class Peano {

        companion object {

            fun fromInt(n: Int): Peano = (0 until n).fold(Z as Peano) { acc, _ -> S(acc) }
        }

        fun toInt(): Int = sequence().count() - 1

        object Z : Peano() {
            override fun toString() = "Z"
        }

        data class S(val n: Peano) : Peano() {
            override fun toString() = "S($n)"
        }

        private
        fun sequence() = generateSequence(this) { previous ->
            when (previous) {
                is Z -> null
                is S -> previous.n
            }
        }
    }
}
