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

package io.keyss.view_record.video

import android.media.MediaCodec

/**
 * Created by pedro on 18/9/23.
 */
interface EncoderErrorCallback {
    fun onCodecError(type: String, e: MediaCodec.CodecException)

    /**
     * @return indicate if should try reset encoder, 编码过程中 input 和 output 报的错
     */
    fun onEncodeError(type: String, e: IllegalStateException): Boolean = true
}
