/*
 * Copyright (C) 2023 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.keyss.view_record.base

/**
 * Created by pedro on 17/02/18.
 */
class Frame {
    var buffer: ByteArray
    var offset: Int
    var size: Int
    var timeStamp: Long

    constructor(buffer: ByteArray, timeStamp: Long = System.nanoTime() / 1000) {
        this.buffer = buffer
        offset = 0
        size = buffer.size
        this.timeStamp = timeStamp
    }

    /**
     * Used with audio frame
     */
    constructor(buffer: ByteArray, offset: Int, size: Int, timeStamp: Long = System.nanoTime() / 1000) {
        this.buffer = buffer
        this.offset = offset
        this.size = size
        this.timeStamp = timeStamp
    }
}
