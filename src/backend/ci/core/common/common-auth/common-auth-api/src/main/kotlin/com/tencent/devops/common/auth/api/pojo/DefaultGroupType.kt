
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
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.common.auth.api.pojo

/**
 * 项目角色组
 */
enum class DefaultGroupType(val value: String, val displayName: String) {
    MANAGER("manager", "管理员"), // 管理员
    DEVELOPER("developer", "开发人员"), // 开发人员
    MAINTAINER("maintainer", "运维人员"), // 运维人员
    TESTER("tester", "测试人员"), // 测试人员
    PM("pm", "产品人员"), // 产品人员
    QC("qc", "质量管理员"); // 质量管理员

    companion object {
        fun get(value: String): DefaultGroupType {
            values().forEach {
                if (value == it.value) return it
            }
            throw IllegalArgumentException("No enum for constant $value")
        }

        fun contains(value: String): Boolean {
            values().forEach {
                if (value == it.value) return true
            }
            return false
        }

        fun containsDisplayName(displayName: String): Boolean {
            values().forEach {
                if (displayName == it.displayName) return true
            }
            return false
        }

        fun getAll(): List<DefaultGroupType> {
            val allGroup = mutableListOf<DefaultGroupType>()
            allGroup.add(DEVELOPER)
            allGroup.add(MANAGER)
            allGroup.add(MAINTAINER)
            allGroup.add(TESTER)
            allGroup.add(PM)
            allGroup.add(QC)
            return allGroup
        }
    }
}
