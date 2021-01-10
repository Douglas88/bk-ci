/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.engine.common

/**
 *
 * @version 1.0
 */
object VMUtils {

    fun genStageId(seq: Int) = "stage-$seq"

    fun genStopVMTaskId(seq: Int) = "${getStopVmLabel()}$seq"

    fun genEndPointTaskId(seq: Int) = "${getEndLabel()}$seq"

    fun genVMSeq(containerSeq: Int, taskSeq: Int): Int = containerSeq * 1000 + taskSeq

    fun genStartVMTaskId(containerSeq: String) = "${getStartVmLabel()}$containerSeq"

    fun getStopVmLabel() = "stopVM-"

    fun getCleanVmLabel() = "Clean_Job#"

    fun getStartVmLabel() = "startVM-"

    fun getPrepareVmLabel() = "Prepare_Job#"

    fun getWaitLabel() = "Wait_Finish_Job#"

    fun getEndLabel() = "end-"
}
