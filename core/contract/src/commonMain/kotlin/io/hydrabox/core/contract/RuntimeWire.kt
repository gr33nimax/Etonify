package io.hydrabox.core.contract

/**
 * Platform-neutral wire schema shared by Android Binder and the future desktop pipe.
 *
 * This deliberately uses only common Kotlin so transport implementations, rather
 * than the contract, own their platform serialization APIs.
 */
object RuntimeWire {
    private const val SCHEMA = "6"

    fun encode(command: RuntimeCommand): ByteArray = when (command) {
        is RuntimeCommand.Start -> pack("command", "start", command.mode.name)
        RuntimeCommand.Stop -> pack("command", "stop")
        RuntimeCommand.Reload -> pack("command", "reload")
        is RuntimeCommand.SelectOutbound -> pack("command", "select", text(command.groupId), text(command.outboundId))
        is RuntimeCommand.NetworkChanged -> pack("command", "network", command.generation.value.toString())
    }.encodeToByteArray()

    fun decodeCommand(bytes: ByteArray): RuntimeCommand {
        val fields = unpack(bytes.decodeToString(), "command")
        return when (fields.removeAt(0)) {
            "start" -> RuntimeCommand.Start(RuntimeMode.valueOf(fields.single()))
            "stop" -> RuntimeCommand.Stop.also { check(fields.isEmpty()) }
            "reload" -> RuntimeCommand.Reload.also { check(fields.isEmpty()) }
            "select" -> RuntimeCommand.SelectOutbound(readText(fields.removeAt(0)), readText(fields.removeAt(0))).also { check(fields.isEmpty()) }
            "network" -> RuntimeCommand.NetworkChanged(NetworkGeneration(fields.single().toLong()))
            else -> error("Unknown runtime command")
        }
    }

    fun encode(snapshot: RuntimeSnapshot): ByteArray = pack(
        "snapshot",
        text(snapshot.processEpoch.value),
        snapshot.commandGeneration.value.toString(),
        snapshot.runtimeGeneration.value.toString(),
        snapshot.networkGeneration.value.toString(),
        snapshot.lastEventSequence.value.toString(),
        snapshot.state.name,
        snapshot.mode?.name ?: "",
        snapshot.selectedOutbounds.size.toString(),
        *snapshot.selectedOutbounds.flatMap { listOf(text(it.groupId), text(it.outboundId)) }.toTypedArray(),
        snapshot.observedOutbounds.size.toString(),
        *snapshot.observedOutbounds.flatMap { listOf(text(it.groupId), text(it.outboundId)) }.toTypedArray(),
        text(snapshot.transportHealth.transportTag),
        snapshot.transportHealth.state.name,
        snapshot.transportHealth.activeLanes.toString(),
        snapshot.transportHealth.totalLanes.toString(),
        snapshot.transportHealth.applicable.toString(),
        snapshot.transportHealth.runtimeGeneration.value.toString(),
        snapshot.transportHealth.networkGeneration.value.toString(),
        failure(snapshot.transportHealth.failure),
        snapshot.transportHealth.retryAfterMillis.toString(),
        snapshot.transportHealth.quicRttMillis.toString(),
        failure(snapshot.lastFailure),
        snapshot.traffic.available.toString(),
        snapshot.traffic.uplink.toString(),
        snapshot.traffic.downlink.toString(),
        snapshot.traffic.uplinkTotal.toString(),
        snapshot.traffic.downlinkTotal.toString(),
        snapshot.traffic.connectionsOut.toString(),
        snapshot.latencies.size.toString(),
        *snapshot.latencies.flatMap {
            listOf(
                text(it.tag), it.delayMillis.toString(), text(it.status),
                it.observedAtMillis.toString(), it.staleAfterMillis.toString(),
                it.ageSeconds.toString(), it.stale.toString(),
            )
        }.toTypedArray(),
        snapshot.edgeLatencies.size.toString(),
        *snapshot.edgeLatencies.flatMap {
            listOf(
                text(it.tag), it.delayMillis.toString(), text(it.status),
                it.observedAtMillis.toString(), it.staleAfterMillis.toString(),
                it.ageSeconds.toString(), it.stale.toString(),
            )
        }.toTypedArray(),
        snapshot.connectedAtElapsedRealtimeMillis?.toString() ?: "",
    ).encodeToByteArray()

    fun decodeSnapshot(bytes: ByteArray): RuntimeSnapshot {
        val fields = unpack(bytes.decodeToString(), "snapshot")
        val processEpoch = ProcessEpoch(readText(fields.removeAt(0)))
        val commandGeneration = CommandGeneration(fields.removeAt(0).toLong())
        val runtimeGeneration = RuntimeGeneration(fields.removeAt(0).toLong())
        val networkGeneration = NetworkGeneration(fields.removeAt(0).toLong())
        val eventSequence = EventSequence(fields.removeAt(0).toLong())
        val state = RuntimeState.valueOf(fields.removeAt(0))
        val mode = fields.removeAt(0).ifEmpty { null }?.let(RuntimeMode::valueOf)
        val outbounds = List(fields.removeAt(0).toInt()) {
            OutboundSelection(readText(fields.removeAt(0)), readText(fields.removeAt(0)))
        }
        val observed = List(fields.removeAt(0).toInt()) {
            OutboundSelection(readText(fields.removeAt(0)), readText(fields.removeAt(0)))
        }
        val health = TransportHealth(
            transportTag = readText(fields.removeAt(0)),
            state = TransportHealthState.valueOf(fields.removeAt(0)),
            activeLanes = fields.removeAt(0).toInt(),
            totalLanes = fields.removeAt(0).toInt(),
            applicable = fields.removeAt(0).toBooleanStrict(),
            runtimeGeneration = RuntimeGeneration(fields.removeAt(0).toLong()),
            networkGeneration = NetworkGeneration(fields.removeAt(0).toLong()),
            failure = readFailure(fields.removeAt(0)),
            retryAfterMillis = fields.removeAt(0).toLong(),
            quicRttMillis = fields.removeAt(0).toLong(),
        )
        val lastFailure = readFailure(fields.removeAt(0))
        val traffic = TrafficCounters(
            available = fields.removeAt(0).toBooleanStrict(),
            uplink = fields.removeAt(0).toLong(),
            downlink = fields.removeAt(0).toLong(),
            uplinkTotal = fields.removeAt(0).toLong(),
            downlinkTotal = fields.removeAt(0).toLong(),
            connectionsOut = fields.removeAt(0).toInt(),
        )
        val latencies = List(fields.removeAt(0).toInt()) {
            OutboundLatency(
                tag = readText(fields.removeAt(0)),
                delayMillis = fields.removeAt(0).toInt(),
                status = readText(fields.removeAt(0)),
                observedAtMillis = fields.removeAt(0).toLong(),
                staleAfterMillis = fields.removeAt(0).toLong(),
                ageSeconds = fields.removeAt(0).toLong(),
                stale = fields.removeAt(0).toBooleanStrict(),
            )
        }
        val edgeLatencies = List(fields.removeAt(0).toInt()) {
            OutboundLatency(
                tag = readText(fields.removeAt(0)),
                delayMillis = fields.removeAt(0).toInt(),
                status = readText(fields.removeAt(0)),
                observedAtMillis = fields.removeAt(0).toLong(),
                staleAfterMillis = fields.removeAt(0).toLong(),
                ageSeconds = fields.removeAt(0).toLong(),
                stale = fields.removeAt(0).toBooleanStrict(),
            )
        }
        val connectedAtElapsedRealtimeMillis = fields.removeAt(0).ifEmpty { null }?.toLong()
        check(fields.isEmpty())
        return RuntimeSnapshot(
            processEpoch = processEpoch,
            commandGeneration = commandGeneration,
            runtimeGeneration = runtimeGeneration,
            networkGeneration = networkGeneration,
            lastEventSequence = eventSequence,
            state = state,
            mode = mode,
            selectedOutbounds = outbounds,
            observedOutbounds = observed,
            transportHealth = health,
            lastFailure = lastFailure,
            traffic = traffic,
            latencies = latencies,
            edgeLatencies = edgeLatencies,
            connectedAtElapsedRealtimeMillis = connectedAtElapsedRealtimeMillis,
        )
    }

    private fun failure(value: RuntimeFailure?): String = value?.let { "${it.domain.name},${it.code.name},${it.retryable}" } ?: ""

    private fun readFailure(value: String): RuntimeFailure? = value.ifEmpty { return null }.split(',').let {
        check(it.size == 3)
        RuntimeFailure(FailureDomain.valueOf(it[0]), HydraCoreErrorCode.valueOf(it[1]), it[2].toBooleanStrict())
    }

    private fun pack(type: String, vararg fields: String): String = listOf(SCHEMA, type, *fields).joinToString("|")

    private fun unpack(value: String, type: String): MutableList<String> = value.split('|').toMutableList().also {
        check(it.removeAt(0) == SCHEMA && it.removeAt(0) == type) { "Unsupported runtime wire message" }
    }

    private fun text(value: String): String = value.map { it.code.toString(16).padStart(4, '0') }.joinToString("")

    private fun readText(value: String): String {
        check(value.length % 4 == 0)
        return buildString { value.chunked(4).forEach { append(it.toInt(16).toChar()) } }
    }
}
