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

package com.tencent.devops.process.engine.service

import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.auth.api.AuthProjectApi
import com.tencent.devops.common.auth.api.pojo.BkAuthGroup
import com.tencent.devops.common.auth.code.PipelineAuthServiceCode
import com.tencent.devops.common.pipeline.enums.ProjectPipelineCallbackStatus
import com.tencent.devops.common.pipeline.event.CallBackEvent
import com.tencent.devops.common.service.trace.TraceTag
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.dao.ProjectPipelineCallbackDao
import com.tencent.devops.process.dao.ProjectPipelineCallbackHistoryDao
import com.tencent.devops.process.pojo.CallBackHeader
import com.tencent.devops.process.pojo.CreateCallBackResult
import com.tencent.devops.process.pojo.ProjectPipelineCallBack
import com.tencent.devops.process.pojo.ProjectPipelineCallBackHistory
import com.tencent.devops.process.pojo.pipeline.enums.CallBackNetWorkRegionType
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Suppress("ALL")
@Service
class ProjectPipelineCallBackService @Autowired constructor(
    private val dslContext: DSLContext,
    val authProjectApi: AuthProjectApi,
    private val pipelineAuthServiceCode: PipelineAuthServiceCode,
    private val projectPipelineCallbackDao: ProjectPipelineCallbackDao,
    private val projectPipelineCallbackHistoryDao: ProjectPipelineCallbackHistoryDao,
    private val projectPipelineCallBackUrlGenerator: ProjectPipelineCallBackUrlGenerator
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ProjectPipelineCallBackService::class.java)
        private val JSON = MediaType.parse("application/json;charset=utf-8")
    }

    fun createCallBack(
        userId: String,
        projectId: String,
        url: String,
        region: CallBackNetWorkRegionType?,
        event: String,
        secretToken: String?
    ): CreateCallBackResult {
        // 验证用户是否为管理员
        validAuth(userId, projectId, BkAuthGroup.MANAGER)
        // 验证url的合法性
        val regex = Regex(
            pattern = "(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]",
            option = RegexOption.IGNORE_CASE
        )
        val regexResult = url.matches(regex)
        if (!regexResult) {
            throw ErrorCodeException(errorCode = ProcessMessageCode.ERROR_CALLBACK_URL_INVALID)
        }
        val callBackUrl = projectPipelineCallBackUrlGenerator.generateCallBackUrl(
            region = region,
            url = url
        )
        if (event.isBlank()) {
            throw ParamBlankException("Invalid event")
        }
        val events = event.split(",").map {
            CallBackEvent.valueOf(it.trim())
        }

        val successEvents = mutableListOf<String>()
        val failureEvents = mutableMapOf<String, String>()
        events.forEach {
            try {
                val projectPipelineCallBack = ProjectPipelineCallBack(
                    projectId = projectId,
                    callBackUrl = callBackUrl,
                    events = it.name,
                    secretToken = secretToken
                )
                projectPipelineCallbackDao.save(
                    dslContext = dslContext,
                    projectId = projectPipelineCallBack.projectId,
                    events = projectPipelineCallBack.events,
                    userId = userId,
                    callbackUrl = projectPipelineCallBack.callBackUrl,
                    secretToken = projectPipelineCallBack.secretToken
                )
                successEvents.add(it.name)
            } catch (e: Throwable) {
                logger.error("Fail to create callback|$projectId|${it.name}|$callBackUrl", e)
                failureEvents[it.name] = e.message ?: "创建callback失败"
            }
        }
        return CreateCallBackResult(
            successEvents = successEvents,
            failureEvents = failureEvents
        )
    }

    fun listProjectCallBack(projectId: String, events: String): List<ProjectPipelineCallBack> {
        val list = mutableListOf<ProjectPipelineCallBack>()
        val records = projectPipelineCallbackDao.listProjectCallback(
            dslContext = dslContext,
            projectId = projectId,
            events = events
        )
        records.forEach {
            list.add(
                ProjectPipelineCallBack(
                    id = it.id,
                    projectId = it.projectId,
                    callBackUrl = it.callbackUrl,
                    events = it.events,
                    secretToken = it.secretToken
                )
            )
        }
        return list
    }

    fun listByPage(
        userId: String,
        projectId: String,
        offset: Int,
        limit: Int
    ): SQLPage<ProjectPipelineCallBack> {
        checkParam(userId, projectId)
        // 验证用户是否有权限查看
        validAuth(userId, projectId)
        val count = projectPipelineCallbackDao.countByPage(dslContext, projectId)
        val records = projectPipelineCallbackDao.listByPage(dslContext, projectId, offset, limit)
        return SQLPage(
            count,
            records.map {
                ProjectPipelineCallBack(
                    id = it.id,
                    projectId = it.projectId,
                    callBackUrl = it.callbackUrl,
                    events = it.events,
                    secretToken = null
                )
            }
        )
    }

    fun delete(userId: String, projectId: String, id: Long) {
        checkParam(userId, projectId)
        validAuth(userId, projectId, BkAuthGroup.MANAGER)
        projectPipelineCallbackDao.get(
            dslContext = dslContext,
            id = id
        ) ?: throw ErrorCodeException(
            errorCode = ProcessMessageCode.ERROR_CALLBACK_NOT_FOUND,
            defaultMessage = "回调记录($id)不存在",
            params = arrayOf(id.toString())
        )
        projectPipelineCallbackDao.deleteById(
            dslContext = dslContext,
            id = id
        )
    }

    fun createHistory(
        projectPipelineCallBackHistory: ProjectPipelineCallBackHistory
    ) {
        with(projectPipelineCallBackHistory) {
            projectPipelineCallbackHistoryDao.create(
                dslContext = dslContext,
                projectId = projectId,
                callBackUrl = callBackUrl,
                events = events,
                status = status,
                errorMsg = errorMsg,
                requestHeaders = requestHeaders?.let { JsonUtil.toJson(it) },
                requestBody = requestBody,
                responseCode = responseCode,
                responseBody = responseBody,
                startTime = startTime,
                endTime = endTime
            )
        }
    }

    fun getHistory(
        userId: String,
        projectId: String,
        id: Long
    ): ProjectPipelineCallBackHistory? {
        val record = projectPipelineCallbackHistoryDao.get(dslContext, id) ?: return null
        return projectPipelineCallbackHistoryDao.convert(record)
    }

    fun listHistory(
        userId: String,
        projectId: String,
        callBackUrl: String,
        events: String,
        startTime: Long?,
        endTime: Long?,
        offset: Int,
        limit: Int
    ): SQLPage<ProjectPipelineCallBackHistory> {
        checkParam(userId, projectId)
        // 验证用户是否有权限查看
        validAuth(userId, projectId)
        var startTimeTemp = startTime
        if (startTimeTemp == null) {
            startTimeTemp = LocalDateTime.of(LocalDate.now(), LocalTime.MIN).timestampmilli()
        }
        var endTimeTemp = endTime
        if (endTimeTemp == null) {
            endTimeTemp = LocalDateTime.of(LocalDate.now(), LocalTime.MAX).timestampmilli()
        }
        val url = projectPipelineCallBackUrlGenerator.encodeCallbackUrl(url = callBackUrl)
        logger.info("list callback history param|$projectId|$events|$startTimeTemp|$endTimeTemp|$url")
        val count = projectPipelineCallbackHistoryDao.count(
            dslContext = dslContext,
            projectId = projectId,
            callBackUrl = url,
            events = events,
            startTime = startTimeTemp,
            endTime = endTimeTemp
        )
        val records = projectPipelineCallbackHistoryDao.list(
            dslContext = dslContext,
            projectId = projectId,
            callBackUrl = url,
            events = events,
            startTime = startTimeTemp,
            endTime = endTimeTemp,
            offset = offset,
            limit = limit
        )
        return SQLPage(
            count,
            records.map {
                projectPipelineCallbackHistoryDao.convert(it)
            }
        )
    }

    fun retry(
        userId: String,
        projectId: String,
        id: Long
    ) {
        checkParam(userId, projectId)
        validAuth(userId, projectId, BkAuthGroup.MANAGER)
        val record = getHistory(userId, projectId, id) ?: throw ErrorCodeException(
            errorCode = ProcessMessageCode.ERROR_CALLBACK_HISTORY_NOT_FOUND,
            defaultMessage = "重试的回调历史记录($id)不存在",
            params = arrayOf(id.toString())
        )

        val requestBuilder = Request.Builder()
            .url(record.callBackUrl)
            .post(RequestBody.create(JSON, record.requestBody))
        record.requestHeaders?.filter {
            it.name != TraceTag.TRACE_HEADER_DEVOPS_BIZID
        }?.forEach {
            requestBuilder.addHeader(it.name, it.value)
        }
        val request = requestBuilder.header(TraceTag.TRACE_HEADER_DEVOPS_BIZID, TraceTag.buildBiz()).build()

        val startTime = System.currentTimeMillis()
        var responseCode: Int? = null
        var responseBody: String? = null
        var errorMsg: String? = null
        var status = ProjectPipelineCallbackStatus.SUCCESS
        try {
            OkhttpUtils.doHttp(request).use { response ->
                if (response.code() != 200) {
                    logger.warn("[${record.projectId}]|CALL_BACK|url=${record.callBackUrl}| code=${response.code()}")
                    throw ErrorCodeException(
                        statusCode = response.code(),
                        errorCode = ProcessMessageCode.ERROR_CALLBACK_REPLY_FAIL,
                        defaultMessage = "回调重试失败"
                    )
                } else {
                    logger.info("[${record.projectId}]|CALL_BACK|url=${record.callBackUrl}| code=${response.code()}")
                }
                responseCode = response.code()
                responseBody = response.body()?.string()
                errorMsg = response.message()
            }
        } catch (e: Exception) {
            logger.error("[$projectId]|[$userId]|CALL_BACK|url=${record.callBackUrl} error", e)
            errorMsg = e.message
            status = ProjectPipelineCallbackStatus.FAILED
        } finally {
            createHistory(
                ProjectPipelineCallBackHistory(
                    projectId = projectId,
                    callBackUrl = record.callBackUrl,
                    events = record.events,
                    status = status.name,
                    errorMsg = errorMsg,
                    requestHeaders = request.headers().names().map {
                        CallBackHeader(
                            name = it,
                            value = request.header(it) ?: ""
                        )
                    },
                    requestBody = record.requestBody,
                    responseCode = responseCode,
                    responseBody = responseBody,
                    startTime = startTime,
                    endTime = System.currentTimeMillis()
                )
            )
        }
    }

    private fun checkParam(
        userId: String,
        projectId: String
    ) {
        if (userId.isBlank()) {
            throw ParamBlankException("invalid userId")
        }
        if (projectId.isBlank()) {
            throw ParamBlankException("Invalid projectId")
        }
    }

    private fun validAuth(userId: String, projectId: String, group: BkAuthGroup? = null) {
        if (!authProjectApi.isProjectUser(userId, pipelineAuthServiceCode, projectId, group)) {
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.USER_NEED_PROJECT_X_PERMISSION,
                params = arrayOf(userId, projectId)
            )
        }
    }
}
