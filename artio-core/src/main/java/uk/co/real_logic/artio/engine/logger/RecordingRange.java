/*
 * Copyright 2015-2018 Real Logic Ltd, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.logger;

import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;

import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_LONG;

public final class RecordingRange
{
    final long recordingId;
    final long sessionId;
    long position = MISSING_LONG;
    int length;
    int count;

    RecordingRange(final long recordingId, final long sessionId)
    {
        this.recordingId = recordingId;
        this.sessionId = sessionId;
        this.count = 0;
    }

    void add(final long addPosition, final int addLength)
    {
        final long currentPosition = this.position;

        if (currentPosition == MISSING_LONG)
        {
            this.position = addPosition;
            this.length = addLength;
            return;
        }

        final long currentEnd = currentPosition + this.length;
        final long addEnd = addPosition + addLength;
        final long newEnd = Math.max(currentEnd, addEnd);

        if (currentPosition < addPosition)
        {
            // Add to the end
            this.length = (int)(newEnd - currentPosition);
        }
        else if (addPosition < currentPosition)
        {
            // Add to the start
            this.position = addPosition;
            this.length = (int)(newEnd - addPosition);
        }
        else
        {
            DebugLogger.log(LogTag.INDEX, "currentPosition == addPosition, %d", currentPosition);
        }
    }

    @Override
    public String toString()
    {
        return "RecordingRange{" +
            "recordingId=" + recordingId +
            ", sessionId=" + sessionId +
            ", position=" + position +
            ", length=" + length +
            ", count=" + count +
            '}';
    }
}
