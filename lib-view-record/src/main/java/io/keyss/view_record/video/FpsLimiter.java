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

package io.keyss.view_record.video;


/**
 * 2024/04/04 修改
 */
public class FpsLimiter {
    private long lastFrameTime = System.currentTimeMillis();
    private double ratioF = 1000.0 / 30;

    public void setFPS(int fps) {
        setCurrentFrameTime();
        ratioF = 1000.0 / fps;
    }

    /**
     * 大于0表示需要等待，小于等于0表示不需要等待
     *
     * @return 返回需要等待的时间
     */
    public long limitFPS() {
        // 距离上一帧时间
        long sinceLastFrameTime = System.currentTimeMillis() - lastFrameTime;
        return (long) (ratioF - sinceLastFrameTime);
    }

    public void setCurrentFrameTime() {
        setLastFrameTime(System.currentTimeMillis());
    }

    public void setLastFrameTime(long lastFrameTime) {
        this.lastFrameTime = lastFrameTime;
    }
}
