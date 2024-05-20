/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "jni.h"
#include "nio.h"
#include "nio_util.h"
#include "sun_nio_ch_PipeDispatcherImpl.h"

// MAX_SKIP_BUFFER_SIZE is used to determine the maximum buffer size to
// use when skipping.
static const ssize_t MAX_SKIP_BUFFER_SIZE = 4096;

JNIEXPORT jlong JNICALL
Java_sun_nio_ch_PipeDispatcherImpl_skip0(JNIEnv *env, jclass cl, jobject fdo, jlong n)
{
    if (n < 1)
        return 0;

    const jint fd = fdval(env, fdo);

    const long bs = n < MAX_SKIP_BUFFER_SIZE ? (long) n : MAX_SKIP_BUFFER_SIZE;
    char buf[bs];
    jlong tn = 0;

    for (;;) {
        const jlong remaining = n - tn;
        const ssize_t count = remaining < bs ? (ssize_t) remaining : bs;
        const ssize_t nr = read(fd, buf, count);
        if (nr < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                return tn;
            } else if (errno == EINTR) {
                return IOS_INTERRUPTED;
            } else {
                JNU_ThrowIOExceptionWithLastError(env, "read");
                return IOS_THROWN;
            }
        }
        if (nr > 0)
            tn += nr;
        if (nr == bs)
            continue;
        return tn;
    }
}
