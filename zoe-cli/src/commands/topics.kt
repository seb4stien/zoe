// Copyright (c) 2020 Adevinta.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package com.adevinta.oss.zoe.cli.commands

import com.adevinta.oss.zoe.cli.config.Format
import com.adevinta.oss.zoe.cli.utils.batches
import com.adevinta.oss.zoe.cli.utils.fetch
import com.adevinta.oss.zoe.core.functions.PartitionProgress
import com.adevinta.oss.zoe.core.utils.logger
import com.adevinta.oss.zoe.core.utils.toJsonNode
import com.adevinta.oss.zoe.service.*
import com.adevinta.oss.zoe.service.utils.userError
import com.fasterxml.jackson.databind.node.ArrayNode
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.io.InputStream
import java.lang.Integer.min
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.ZonedDateTime
import java.util.*
import kotlin.math.roundToInt


@ExperimentalCoroutinesApi
@FlowPreview
class TopicsCommand : CliktCommand(
    name = "topics",
    help = "Inspect, produce or consume from topics",
    printHelpOnEmptyArgs = true
) {

    override fun run() {}
}

@ExperimentalCoroutinesApi
@FlowPreview
class TopicsList : CliktCommand(name = "list", help = "list topics"), KoinComponent {

    private val all by option("-a", "--all", help = "Also list internal topics").flag(default = false)

    private val ctx by inject<CliContext>()
    private val service by inject<ZoeService>()

    override fun run() = runBlocking {
        val cluster = ctx.requireCluster()
        val response = service.listTopics(cluster, userTopicsOnly = !all)
        ctx.term.output.format(response.topics.map { it.topic }.toJsonNode()) { echo(it) }
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
class TopicsDescribe : CliktCommand(name = "describe", help = "describe a topic"), KoinComponent {

    private val topic by argument("topic", help = "Topic to read (real or alias)").convert { TopicAliasOrRealName(it) }

    private val ctx by inject<CliContext>()
    private val service by inject<ZoeService>()

    override fun run() = runBlocking {
        val cluster = ctx.requireCluster()
        val response = service.describeTopic(cluster, topic) ?: userError("topic not found : ${topic.value}")
        ctx.term.output.format(response.toJsonNode()) { echo(it) }
    }

}

@FlowPreview
@ExperimentalCoroutinesApi
class TopicsConsume : CliktCommand(name = "consume", help = "Consumes messages from a topic"), KoinComponent {
    private val from: Duration
            by option(help = "Amount of time to go back in the past")
                .convert { Duration.parse(it) }
                .default(Duration.ofHours(1))

    private val filters: List<String> by option("-f", "--filter", help = "Jmespath expression filters").multiple()
    private val formatter by option("--formatter").default("raw")
    private val query: String? by option("--query", help = "Jmespath query to execute on each record")

    private val maxRecords: Int
            by option("-n", "--max-records", help = "Max number of records to output").int().default(Int.MAX_VALUE)

    private val recordsPerBatch: Int?
            by option("--records-per-batch", help = "Max records per lambda call")
                .int()

    private val timeoutPerBatch: Long
            by option("--timeout-per-batch", help = "Timeout per lambda call")
                .long()
                .default(15000L)

    private val parallelism: Int
            by option("-j", "--jobs", help = "Number of readers to spin up in parallel")
                .int()
                .default(1)

    private val continuously: Boolean
            by option("--continuously", help = "Contiously read the topic")
                .flag(default = false)
                .validate {
                    if (it && ctx.term.output != Format.Raw)
                        fail("cannot use '--continuously' with output : ${ctx.term.output}")
                }

    private val verbose: Boolean
            by option(
                "-v",
                "--verbose",
                help = "Use this flag to have the offsets, timestamp and other informations along with the message"
            ).flag(default = false)

    private val topic: TopicAliasOrRealName
            by argument("topic", help = "Target topic to read (alias or real)").convert { TopicAliasOrRealName(it) }

    private val ctx by inject<CliContext>()
    private val service by inject<ZoeService>()

    override fun run() = runBlocking {
        val cluster = ctx.requireCluster()
        val from = ConsumeFrom.Timestamp(ts = ZonedDateTime.now().minus(from).toEpochSecond() * 1000)
        val stop = if (continuously) StopCondition.Continuously else StopCondition.TopicEnd
        val recordsPerBatch = recordsPerBatch ?: (if (continuously) min(maxRecords, 20) else maxRecords)

        val records =
            service
                .read(
                    cluster,
                    topic,
                    from,
                    filters,
                    query,
                    parallelism,
                    recordsPerBatch,
                    timeoutPerBatch,
                    formatter,
                    stop
                )
                .onEach { if (it is RecordOrProgress.Progress && !continuously) log(it.progress) }
                .filter { it is RecordOrProgress.Record }
                .map { it as RecordOrProgress.Record }
                .map { if (verbose) it.record.toJsonNode() else it.record.formatted }
                .take(maxRecords)

        ctx.term.output.format(records) { echo(it) }
    }

    private fun log(progress: Iterable<PartitionProgress>) = progress.forEach {
        it.progress.run {
            val message =
                "progress on partition ${String.format("%02d", it.partition)}\t" +
                        "timestamp -> ${currentTimestamp?.let { ts -> dateFmt.format(Date(ts)) }}\t" +
                        "consumed -> $recordsCount / ${it.latestOffset - startOffset} (${it.percent()}%)"

            logger.info(ctx.term.colors.yellow(message))
        }
    }

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private fun PartitionProgress.percent(): Int = with(progress) {
        val percent = ((currentOffset - startOffset) / (latestOffset - startOffset).toDouble() * 100)
        if (percent.isNaN()) -1 else percent.roundToInt()
    }

}

@FlowPreview
@ExperimentalCoroutinesApi
class TopicsProduce : CliktCommand(name = "produce", help = "produce messages into topics"), KoinComponent {

    private val dryRun by option("--dry-run", help = "Do not actually produce records").flag(default = false)
    private val topic
            by option("-t", "--topic", help = "Topic to write to")
                .convert { TopicAliasOrRealName(it) }
                .required()
    private val subject by option("--subject", help = "Avro subject name to use")
    private val keyPath by option("-k", "--key-path", help = "Jmespath expression to extract the key")
    private val valuePath by option("-v", "--value-path", help = "Jmespath expression to extract the value")
    private val timestampPath by option("--ts-path", help = "Jmespath expression to extract the timestamp")
    private val streaming by option("--streaming", help = "Read data line by line continuously").flag(default = false)
    private val timeoutMs by option("--timeout", help = "Timeout in millis").long().default(Long.MAX_VALUE)
    private val fromStdin by option("--from-stdin", help = "Consume data from stdin").flag(default = false)
    private val fromFile
            by option("--from-file", help = "Consume data from a json file")
                .file(exists = true, fileOkay = true, readable = true)

    private val ctx by inject<CliContext>()
    private val service by inject<ZoeService>()

    override fun run() = runBlocking {
        val cluster = ctx.requireCluster()
        val input: InputStream = when {
            fromStdin -> System.`in`
            fromFile != null -> fromFile!!.inputStream()
            else -> userError("either use '--from-stdin' or '--from-file'")
        }

        withTimeout(timeoutMs) {
            fetch(input, streaming = streaming)
                .map { it.toJsonNode() }
                .let { flow ->
                    if (!streaming) {
                        flow.map {
                            require(it is ArrayNode) { "invalid data : requires a json array" }
                            it.toList()
                        }
                    } else {
                        flow.batches(5000, this)
                    }
                }
                .filter { it.isNotEmpty() }
                .collect { batch ->
                    val response = service.produce(
                        cluster = cluster,
                        topic = topic,
                        subject = subject,
                        messages = batch,
                        keyPath = keyPath,
                        valuePath = valuePath,
                        timestampPath = timestampPath,
                        dejsonifier = null,
                        dryRun = dryRun
                    )
                    ctx.term.output.format(response.toJsonNode()) { echo(it) }
                }
        }
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
fun topicsCommand() = TopicsCommand().subcommands(
    TopicsConsume(),
    TopicsList(),
    TopicsDescribe(),
    TopicsProduce()
)
