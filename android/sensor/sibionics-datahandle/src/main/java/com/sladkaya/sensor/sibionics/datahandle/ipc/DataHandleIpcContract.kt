package com.sladkaya.sensor.sibionics.datahandle.ipc

internal object DataHandleIpcContract {
    const val ACTION_BIND =
        "com.sladkaya.sensor.sibionics.datahandle.action.BIND_PINNED_GATEWAY"
    const val EXTRA_BUNDLE = "bundle"

    const val REGISTER_KEY = 1
    const val AUTHENTICATION = 2
    const val ACTIVATION = 3
    const val TIME_UPDATE = 4
    const val RAW_DATA = 5
    const val RESET = 6
    const val SPLIT = 7

    const val STATUS_OK = 0
    const val STATUS_REJECTED = 1

    const val KEY_STATUS = "status"
    const val KEY_RESULT = "result"
    const val KEY_INPUT = "input"
    const val KEY_INPUT_LENGTH = "input_length"
    const val KEY_OUTPUT = "output"
    const val KEY_OUTPUT_LENGTH = "output_length"
    const val KEY_COMMAND = "command"
    const val KEY_ENCRYPTED = "encrypted"
    const val KEY_VALUE_INT = "value_int"
    const val KEY_VALUE_LONG = "value_long"
    const val KEY_INDEX = "index"
    const val KEY_PACKET = "packet"
    const val KEY_METADATA = "metadata"
    const val KEY_FORMATTED_PAYLOAD = "formatted_payload"
    const val KEY_WORKSPACE = "workspace"
    const val KEY_WORKSPACE_LENGTH = "workspace_length"

    const val MAX_SMALL_BUFFER = 1_024
    const val MAX_PACKET = 250
    const val MAX_FORMATTED_PAYLOAD = 7_232
}
