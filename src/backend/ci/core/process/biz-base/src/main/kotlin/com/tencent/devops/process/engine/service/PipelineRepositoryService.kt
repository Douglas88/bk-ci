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
import com.tencent.devops.common.api.util.DateTimeUtil
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.UUIDUtil
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.pojo.pipeline.PipelineModelAnalysisEvent
import com.tencent.devops.common.notify.enums.NotifyType
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.NormalContainer
import com.tencent.devops.common.pipeline.container.Stage
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.container.VMBuildContainer
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.pipeline.extend.ModelCheckPlugin
import com.tencent.devops.common.pipeline.pojo.BuildNo
import com.tencent.devops.common.pipeline.pojo.element.SubPipelineCallElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.ManualTriggerElement
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.dao.PipelineSettingDao
import com.tencent.devops.process.dao.PipelineSettingVersionDao
import com.tencent.devops.process.engine.cfg.ModelContainerIdGenerator
import com.tencent.devops.process.engine.cfg.ModelTaskIdGenerator
import com.tencent.devops.process.engine.cfg.PipelineIdGenerator
import com.tencent.devops.process.engine.cfg.VersionConfigure
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.process.engine.dao.PipelineBuildSummaryDao
import com.tencent.devops.process.engine.dao.PipelineInfoDao
import com.tencent.devops.process.engine.dao.PipelineModelTaskDao
import com.tencent.devops.process.engine.dao.PipelineResDao
import com.tencent.devops.process.engine.dao.PipelineResVersionDao
import com.tencent.devops.process.engine.dao.template.TemplatePipelineDao
import com.tencent.devops.process.engine.pojo.PipelineInfo
import com.tencent.devops.process.engine.pojo.PipelineModelTask
import com.tencent.devops.process.engine.pojo.event.PipelineCreateEvent
import com.tencent.devops.process.engine.pojo.event.PipelineDeleteEvent
import com.tencent.devops.process.engine.pojo.event.PipelineRestoreEvent
import com.tencent.devops.process.engine.pojo.event.PipelineUpdateEvent
import com.tencent.devops.process.plugin.load.ElementBizRegistrar
import com.tencent.devops.process.pojo.pipeline.DeletePipelineResult
import com.tencent.devops.process.pojo.pipeline.DeployPipelineResult
import com.tencent.devops.process.pojo.pipeline.PipelineSubscriptionType
import com.tencent.devops.process.pojo.setting.PipelineModelVersion
import com.tencent.devops.process.pojo.setting.PipelineRunLockType
import com.tencent.devops.process.pojo.setting.PipelineSetting
import com.tencent.devops.process.pojo.setting.Subscription
import org.joda.time.LocalDateTime
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger
import javax.ws.rs.core.Response

@Suppress("LongParameterList", "LargeClass", "TooManyFunctions", "LongMethod", "ReturnCount")
@Service
class PipelineRepositoryService constructor(
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val modelContainerIdGenerator: ModelContainerIdGenerator,
    private val pipelineIdGenerator: PipelineIdGenerator,
    private val modelTaskIdGenerator: ModelTaskIdGenerator,
    private val dslContext: DSLContext,
    private val pipelineInfoDao: PipelineInfoDao,
    private val pipelineResDao: PipelineResDao,
    private val pipelineModelTaskDao: PipelineModelTaskDao,
    private val pipelineSettingDao: PipelineSettingDao,
    private val pipelineBuildSummaryDao: PipelineBuildSummaryDao,
    private val pipelineJobMutexGroupService: PipelineJobMutexGroupService,
    private val modelCheckPlugin: ModelCheckPlugin,
    private val templatePipelineDao: TemplatePipelineDao,
    private val pipelineResVersionDao: PipelineResVersionDao,
    private val pipelineSettingVersionDao: PipelineSettingVersionDao,
    private val versionConfigure: VersionConfigure
) {

    fun deployPipeline(
        model: Model,
        projectId: String,
        signPipelineId: String?,
        userId: String,
        channelCode: ChannelCode,
        create: Boolean,
        useTemplateSettings: Boolean? = false,
        templateId: String? = null
    ): DeployPipelineResult {

        // 生成流水线ID,新流水线以p-开头，以区分以前旧数据
        val pipelineId = signPipelineId ?: pipelineIdGenerator.getNextId()

        val modelTasks = initModel(
            model = model,
            projectId = projectId,
            pipelineId = pipelineId,
            userId = userId,
            create = create,
            channelCode = channelCode
        )

        val buildNo = (model.stages[0].containers[0] as TriggerContainer).buildNo
        val triggerContainer = model.stages[0].containers[0] as TriggerContainer
        var canManualStartup = false
        var canElementSkip = false
        run lit@{
            triggerContainer.elements.forEach {
                if (it is ManualTriggerElement && it.isElementEnable()) {
                    canManualStartup = true
                    canElementSkip = it.canElementSkip ?: false
                    return@lit
                }
            }
        }

        return if (!create) {
            val pipelineSetting = pipelineSettingDao.getSetting(dslContext, pipelineId)
            update(
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId,
                model = model,
                canManualStartup = canManualStartup,
                canElementSkip = canElementSkip,
                buildNo = buildNo,
                modelTasks = modelTasks,
                channelCode = channelCode,
                maxPipelineResNum = pipelineSetting?.maxPipelineResNum
            )
        } else {
            create(
                projectId = projectId,
                pipelineId = pipelineId,
                model = model,
                userId = userId,
                channelCode = channelCode,
                canManualStartup = canManualStartup,
                canElementSkip = canElementSkip,
                buildNo = buildNo,
                modelTasks = modelTasks,
                useTemplateSettings = useTemplateSettings,
                templateId = templateId
            )
        }
    }

    /**
     * 初始化并检查合法性
     */
    fun initModel(
        model: Model,
        projectId: String,
        pipelineId: String,
        userId: String,
        create: Boolean = true,
        channelCode: ChannelCode
    ): Set<PipelineModelTask> {

        modelCheckPlugin.checkModelIntegrity(model, projectId)

        // 初始化ID 该构建环境下的ID,旧流水引擎数据无法转换为String，仍然是序号的方式
        val modelTasks = mutableSetOf<PipelineModelTask>()
        // 初始化ID 该构建环境下的ID,旧流水引擎数据无法转换为String，仍然是序号的方式
        val containerSeqId = AtomicInteger(0)
        model.stages.forEachIndexed { index, s ->
            s.id = VMUtils.genStageId(index + 1)
            // #4531 对存量的stage审核数据做兼容处理
            s.refreshReviewOption(true)
            if (index == 0) { // 在流程模型中初始化触发类容器
                initTriggerContainer(
                    stage = s,
                    containerSeqId = containerSeqId,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    model = model,
                    userId = userId,
                    modelTasks = modelTasks,
                    channelCode = channelCode,
                    create = create
                )
            } else {
                initOtherContainer(
                    stage = s,
                    projectId = projectId,
                    containerSeqId = containerSeqId,
                    userId = userId,
                    pipelineId = pipelineId,
                    model = model,
                    modelTasks = modelTasks,
                    channelCode = channelCode,
                    create = create
                )
            }
        }

        return modelTasks
    }

    private fun initTriggerContainer(
        stage: Stage,
        containerSeqId: AtomicInteger,
        projectId: String,
        pipelineId: String,
        model: Model,
        userId: String,
        modelTasks: MutableSet<PipelineModelTask>,
        channelCode: ChannelCode,
        create: Boolean
    ) {
        if (stage.containers.size != 1) {
            logger.warn("The trigger stage contain more than one container (${stage.containers.size})")
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.ILLEGAL_PIPELINE_MODEL_JSON,
                defaultMessage = "非法的流水线编排"
            )
        }
        val c = (stage.containers.getOrNull(0) ?: throw ErrorCodeException(
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NEED_JOB,
            defaultMessage = "第一阶段的环境不能为空"
        )) as TriggerContainer
        c.id = containerSeqId.get().toString()
        if (c.containerId.isNullOrBlank()) {
            c.containerId = modelContainerIdGenerator.getNextId()
        }

        var taskSeq = 0
        c.elements.forEach { e ->
            if (e.id.isNullOrBlank()) {
                e.id = modelTaskIdGenerator.getNextId()
            }

            ElementBizRegistrar.getPlugin(e)?.afterCreate(
                element = e,
                projectId = projectId,
                pipelineId = pipelineId,
                pipelineName = model.name,
                userId = userId,
                channelCode = channelCode,
                create = create,
                container = c
            )

            modelTasks.add(
                PipelineModelTask(
                    projectId = projectId,
                    pipelineId = pipelineId,
                    stageId = stage.id!!,
                    containerId = c.id!!,
                    taskId = e.id!!,
                    taskSeq = ++taskSeq,
                    taskName = e.name,
                    atomCode = e.getAtomCode(),
                    atomVersion = e.version,
                    classType = e.getClassType(),
                    taskAtom = e.getTaskAtom(),
                    taskParams = e.genTaskParams(),
                    additionalOptions = e.additionalOptions
                )
            )
        }
    }

    private fun initOtherContainer(
        stage: Stage,
        projectId: String,
        containerSeqId: AtomicInteger,
        userId: String,
        pipelineId: String,
        model: Model,
        modelTasks: MutableSet<PipelineModelTask>,
        channelCode: ChannelCode,
        create: Boolean
    ) {
        if (stage.containers.isEmpty()) {
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NEED_JOB,
                defaultMessage = "阶段的环境不能为空"
            )
        }
        stage.containers.forEach { c ->

            if (c is TriggerContainer) {
                return@forEach
            }

            val mutexGroup = when (c) {
                is VMBuildContainer -> c.mutexGroup
                is NormalContainer -> c.mutexGroup
                else -> null
            }

            // 当mutexGroupName不为空的时候
            if (!mutexGroup?.mutexGroupName.isNullOrBlank()) {
                pipelineJobMutexGroupService.create(
                    projectId = projectId,
                    jobMutexGroupName = mutexGroup!!.mutexGroupName!!
                )
            }

            modelCheckPlugin.checkJob(
                projectId = projectId,
                pipelineId = pipelineId,
                jobContainer = c,
                userId = userId,
                finallyStage = stage.finally
            )

            var taskSeq = 0
            c.id = containerSeqId.incrementAndGet().toString()
            if (c.containerId.isNullOrBlank()) {
                c.containerId = UUIDUtil.generate()
            }
            c.elements.forEach { e ->
                if (e.id.isNullOrBlank()) {
                    e.id = modelTaskIdGenerator.getNextId()
                }

                when (e) {
                    is SubPipelineCallElement -> { // 子流水线循环依赖检查
                        val existPipelines = HashSet<String>()
                        existPipelines.add(pipelineId)
                        checkSubpipeline(projectId, e.subPipelineId, existPipelines)
                    }
                }

                // 补偿动作--未来拆分出来，针对复杂的东西异步处理
                ElementBizRegistrar.getPlugin(e)?.afterCreate(
                    element = e,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    pipelineName = model.name,
                    userId = userId,
                    channelCode = channelCode,
                    create = create,
                    container = c
                )

                modelTasks.add(
                    PipelineModelTask(
                        projectId = projectId,
                        pipelineId = pipelineId,
                        stageId = stage.id!!,
                        containerId = c.id!!,
                        taskId = e.id!!,
                        taskSeq = ++taskSeq,
                        taskName = e.name,
                        atomCode = e.getAtomCode(),
                        atomVersion = e.version,
                        classType = e.getClassType(),
                        taskAtom = e.getTaskAtom(),
                        taskParams = e.genTaskParams(),
                        additionalOptions = e.additionalOptions
                    )
                )
            }
        }
    }

    private fun create(
        projectId: String,
        pipelineId: String,
        model: Model,
        userId: String,
        channelCode: ChannelCode,
        canManualStartup: Boolean,
        canElementSkip: Boolean,
        buildNo: BuildNo?,
        modelTasks: Set<PipelineModelTask>,
        useTemplateSettings: Boolean? = false,
        templateId: String? = null
    ): DeployPipelineResult {

        val taskCount: Int = model.taskCount()
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            pipelineInfoDao.create(
                dslContext = transactionContext,
                pipelineId = pipelineId,
                projectId = projectId,
                version = 1,
                pipelineName = model.name,
                pipelineDesc = model.desc ?: model.name,
                userId = userId,
                channelCode = channelCode,
                manualStartup = canManualStartup,
                canElementSkip = canElementSkip,
                taskCount = taskCount
            )
            model.latestVersion = 1
            pipelineResDao.create(
                dslContext = transactionContext,
                pipelineId = pipelineId,
                creator = userId,
                version = 1,
                model = model
            )
            pipelineResVersionDao.create(
                dslContext = transactionContext,
                pipelineId = pipelineId,
                creator = userId,
                version = 1,
                model = model
            )
            if (model.instanceFromTemplate != true) {
                if (null == pipelineSettingDao.getSetting(transactionContext, pipelineId)) {
                    if (templateId != null && useTemplateSettings == true) {
                        // 沿用模板的配置
                        val setting = getSetting(templateId)
                            ?: throw ErrorCodeException(errorCode = ProcessMessageCode.PIPELINE_SETTING_NOT_EXISTS)
                        setting.pipelineId = pipelineId
                        setting.pipelineName = model.name
                        pipelineSettingDao.saveSetting(dslContext, setting)
                    } else {
                        // #3311
                        // 蓝盾正常的BS渠道的默认没设置setting的，将发通知改成失败才发通知
                        // 而其他渠道的默认没设置则什么通知都设置为不发
                        val notifyTypes = if (channelCode == ChannelCode.BS) {
                            "${NotifyType.EMAIL.name},${NotifyType.RTX.name}"
                        } else {
                            ""
                        }

                        // 特定渠道保留特定版本
                        val filterList = versionConfigure.specChannels.split(",")
                        val maxPipelineResNum = if (channelCode.name in filterList) {
                            versionConfigure.specChannelMaxKeepNum
                        } else {
                            versionConfigure.maxKeepNum
                        }
                        pipelineSettingDao.insertNewSetting(
                            dslContext = transactionContext,
                            projectId = projectId,
                            pipelineId = pipelineId,
                            pipelineName = model.name,
                            failNotifyTypes = notifyTypes,
                            maxPipelineResNum = maxPipelineResNum
                        )
                        pipelineSettingVersionDao.insertNewSetting(
                            dslContext = transactionContext,
                            projectId = projectId,
                            pipelineId = pipelineId,
                            failNotifyTypes = notifyTypes
                        )
                    }
                } else {
                    pipelineSettingDao.updateSetting(
                        dslContext = transactionContext,
                        pipelineId = pipelineId,
                        name = model.name,
                        desc = model.desc ?: ""
                    )
                }
            }
            // 初始化流水线构建统计表
            pipelineBuildSummaryDao.create(dslContext, projectId, pipelineId, buildNo)
            pipelineModelTaskDao.batchSave(transactionContext, modelTasks)
        }

        pipelineEventDispatcher.dispatch(
            PipelineCreateEvent(
                source = "create_pipeline",
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId,
                buildNo = buildNo
            ),
            PipelineModelAnalysisEvent(
                source = "create_pipeline",
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId,
                model = JsonUtil.toJson(model),
                channelCode = channelCode.name
            )
        )
        return DeployPipelineResult(pipelineId, 1)
    }

    private fun update(
        projectId: String,
        pipelineId: String,
        userId: String,
        model: Model,
        canManualStartup: Boolean,
        canElementSkip: Boolean,
        buildNo: BuildNo?,
        modelTasks: Set<PipelineModelTask>,
        channelCode: ChannelCode,
        maxPipelineResNum: Int? = null
    ): DeployPipelineResult {
        val taskCount: Int = model.taskCount()
        var version = 0
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            version = pipelineInfoDao.update(
                dslContext = transactionContext,
                pipelineId = pipelineId,
                userId = userId,
                updateVersion = true,
                pipelineName = null,
                pipelineDesc = null,
                manualStartup = canManualStartup,
                canElementSkip = canElementSkip,
                buildNo = buildNo,
                taskCount = taskCount,
                latestVersion = model.latestVersion
            )
            if (version == 0) {
                // 传过来的latestVersion已经不是最新
                throw ErrorCodeException(errorCode = ProcessMessageCode.ERROR_PIPELINE_IS_NOT_THE_LATEST)
            }
            model.latestVersion = version
            pipelineResDao.create(
                dslContext = transactionContext,
                pipelineId = pipelineId,
                creator = userId,
                version = version,
                model = model
            )
            pipelineResVersionDao.create(
                dslContext = transactionContext,
                pipelineId = pipelineId,
                creator = userId,
                version = version,
                model = model
            )
            if (version > 1 && pipelineResVersionDao.getVersionModelString(
                    dslContext = transactionContext,
                    pipelineId = pipelineId,
                    version = version - 1
                ) == null
            ) {
                // 当ResVersion表中缺失上一个有效版本时需从Res表迁移数据（版本间流水线模型对比有用）
                val lastVersionModelStr = pipelineResDao.getVersionModelString(
                    dslContext = dslContext,
                    pipelineId = pipelineId,
                    version = version - 1
                )
                if (!lastVersionModelStr.isNullOrEmpty()) {
                    pipelineResVersionDao.create(
                        dslContext = transactionContext,
                        pipelineId = pipelineId,
                        creator = userId,
                        version = version - 1,
                        modelString = lastVersionModelStr
                    )
                }
            }
            pipelineModelTaskDao.deletePipelineTasks(
                dslContext = transactionContext,
                projectId = projectId,
                pipelineId = pipelineId
            )
            pipelineResDao.deleteEarlyVersion(transactionContext, pipelineId, beforeVersion = version)
            if (maxPipelineResNum != null) {
                pipelineResVersionDao.deleteEarlyVersion(
                    dslContext = transactionContext,
                    pipelineId = pipelineId,
                    currentVersion = version,
                    maxPipelineResNum = maxPipelineResNum
                )
            }
            pipelineModelTaskDao.batchSave(transactionContext, modelTasks)
        }

        pipelineEventDispatcher.dispatch(
            PipelineUpdateEvent(
                source = "update_pipeline",
                projectId = projectId,
                pipelineId = pipelineId,
                version = version,
                userId = userId,
                buildNo = buildNo
            ),
            PipelineModelAnalysisEvent(
                source = "update_pipeline",
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId,
                model = JsonUtil.toJson(model),
                channelCode = channelCode.name
            )
        )
        return DeployPipelineResult(pipelineId, version)
    }

    fun getPipelineInfo(
        projectId: String?,
        pipelineId: String,
        channelCode: ChannelCode? = null,
        delete: Boolean? = false
    ): PipelineInfo? {
        val template = templatePipelineDao.get(dslContext, pipelineId)
        val templateId = template?.templateId
        return pipelineInfoDao.convert(
            t = pipelineInfoDao.getPipelineInfo(
                dslContext = dslContext,
                projectId = projectId,
                pipelineId = pipelineId,
                channelCode = channelCode,
                delete = delete
            ),
            templateId = templateId
        )
    }

    fun getPipelineInfo(
        pipelineId: String,
        channelCode: ChannelCode? = null,
        delete: Boolean? = false
    ): PipelineInfo? {
        return getPipelineInfo(projectId = null, pipelineId = pipelineId, channelCode = channelCode, delete = delete)
    }

    fun getModel(pipelineId: String, version: Int? = null): Model? {
        var modelString: String?
        if (version == null) { // 取最新版，直接从旧版本表读
            modelString = pipelineResDao.getVersionModelString(dslContext, pipelineId, version) ?: return null
        } else {
            modelString = pipelineResVersionDao.getVersionModelString(dslContext, pipelineId, version)
            if (modelString.isNullOrBlank()) {
                // 兼容处理：取不到再从旧的版本表取
                modelString = pipelineResDao.getVersionModelString(dslContext, pipelineId, version) ?: return null
            }
        }
        return try {
            JsonUtil.to(modelString, Model::class.java)
        } catch (ignored: Exception) {
            logger.error("get process($pipelineId) model fail", ignored)
            null
        }
    }

    fun deletePipeline(
        projectId: String,
        pipelineId: String,
        userId: String,
        channelCode: ChannelCode?,
        delete: Boolean
    ): DeletePipelineResult {

        val record =
            (pipelineInfoDao.getPipelineInfo(dslContext, projectId, pipelineId, channelCode)
                ?: throw ErrorCodeException(
                    errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                    defaultMessage = "要删除的流水线不存在"
                ))

        val pipelineResult = DeletePipelineResult(pipelineId, record.pipelineName, record.version)

        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)

            if (delete) {
                pipelineInfoDao.delete(transactionContext, projectId, pipelineId)
                pipelineResVersionDao.deleteAllVersion(transactionContext, pipelineId)
                pipelineSettingVersionDao.deleteAllVersion(transactionContext, pipelineId)
                pipelineResDao.deleteAllVersion(transactionContext, pipelineId)
                pipelineSettingDao.delete(transactionContext, pipelineId)
                templatePipelineDao.delete(transactionContext, pipelineId)
            } else {
                // 删除前改名，防止名称占用
                val deleteTime = LocalDateTime.now().toString("yyyyMMddHHmm")
                var deleteName = "${record.pipelineName}[$deleteTime]"
                if (deleteName.length > MAX_LEN_FOR_NAME) { // 超过截断，且用且珍惜
                    deleteName = deleteName.substring(0, MAX_LEN_FOR_NAME)
                }

                pipelineInfoDao.softDelete(
                    dslContext = transactionContext,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    changePipelineName = deleteName,
                    userId = userId,
                    channelCode = channelCode
                )
                // 同时要对Setting中的name做设置
                pipelineSettingDao.updateSetting(
                    dslContext = transactionContext,
                    pipelineId = pipelineId,
                    name = deleteName,
                    desc = "DELETE BY $userId in $deleteTime"
                )
                // #4201 标志关联模板为删除
                templatePipelineDao.softDelete(dslContext = transactionContext, pipelineId = pipelineId)
            }

            pipelineModelTaskDao.deletePipelineTasks(transactionContext, projectId, pipelineId)

            pipelineEventDispatcher.dispatch(
                PipelineDeleteEvent(
                    source = "delete_pipeline",
                    projectId = projectId,
                    pipelineId = pipelineId,
                    userId = userId,
                    clearUpModel = delete
                ),
                PipelineModelAnalysisEvent(
                    source = "delete_pipeline",
                    projectId = projectId,
                    pipelineId = pipelineId,
                    userId = userId,
                    model = "",
                    channelCode = record.channel
                )
            )
        }

        return pipelineResult
    }

    fun isPipelineExist(
        projectId: String,
        pipelineName: String,
        channelCode: ChannelCode = ChannelCode.BS,
        excludePipelineId: String?
    ): Boolean {
        return pipelineInfoDao.isNameExist(dslContext, projectId, pipelineName, channelCode, excludePipelineId)
    }

    fun countByProjectIds(projectIds: Set<String>, channelCode: ChannelCode?): Int {
        return pipelineInfoDao.countByProjectIds(dslContext, projectIds, channelCode)
    }

    fun listPipelineNameByIds(
        projectId: String,
        pipelineIds: Set<String>,
        filterDelete: Boolean = true
    ): Map<String, String> {
        val listInfoByPipelineIds =
            pipelineInfoDao.listInfoByPipelineIds(
                dslContext = dslContext,
                projectId = projectId,
                pipelineIds = pipelineIds,
                filterDelete = filterDelete
            )
        return listInfoByPipelineIds.map {
            it.pipelineId to it.pipelineName
        }.toMap()
    }

    fun listPipelineIdByName(
        projectId: String,
        pipelineNames: Set<String>,
        filterDelete: Boolean = true
    ): Map<String, String> {

        if (pipelineNames.isEmpty() || projectId.isBlank()) return mapOf()

        val listInfoByPipelineName =
            pipelineInfoDao.listInfoByPipelineName(
                dslContext = dslContext,
                projectId = projectId,
                pipelineNames = pipelineNames,
                filterDelete = filterDelete
            )
        return listInfoByPipelineName.map {
            it.pipelineName to it.pipelineId
        }.toMap()
    }

    private fun checkSubpipeline(projectId: String, pipelineId: String, existPipelines: HashSet<String>) {

        if (existPipelines.contains(pipelineId)) {
            logger.info("[$projectId|$pipelineId] Sub pipeline call [$existPipelines|$pipelineId]")
            throw ErrorCodeException(
                defaultMessage = "子流水线不允许循环调用",
                errorCode = ProcessMessageCode.ERROR_SUBPIPELINE_CYCLE_CALL
            )
        }
        existPipelines.add(pipelineId)
        val pipeline = getPipelineInfo(projectId, pipelineId)
        if (pipeline == null) {
            logger.warn("The sub pipeline($pipelineId) is not exist")
            return
        }

        val existModel = getModel(pipelineId, pipeline.version)

        if (existModel == null) {
            logger.warn("The pipeline($pipelineId) is not exist")
            return
        }

        val currentExistPipelines = HashSet<String>(existPipelines)
        existModel.stages.forEachIndexed stage@{ index, stage ->
            if (index == 0) {
                // Ignore the trigger container
                return@stage
            }
            stage.containers.forEach container@{ container ->
                if (container !is NormalContainer) {
                    // 只在无构建环境中
                    return@container
                }

                container.elements.forEach element@{ element ->
                    if (element !is SubPipelineCallElement) {
                        return@element
                    }
                    val subpipelineId = element.subPipelineId
                    if (subpipelineId.isBlank()) {
                        logger.warn("The sub pipeline id of pipeline($pipeline) is blank")
                        return@element
                    }
                    val exist = HashSet<String>(currentExistPipelines)
                    checkSubpipeline(projectId = projectId, pipelineId = subpipelineId, existPipelines = exist)
                    existPipelines.addAll(exist)
                }
            }
        }
    }

    fun getBuildNo(pipelineId: String): Int? {
        return pipelineBuildSummaryDao.get(dslContext, pipelineId)?.buildNo
    }

    @Suppress("ComplexMethod", "MagicNumber")
    fun getSetting(pipelineId: String): PipelineSetting? {
        val t = pipelineSettingDao.getSetting(dslContext, pipelineId)
        return if (t != null) {
            val successType = t.successType?.split(",")?.filter { i -> i.isNotBlank() }
                ?.map { type -> PipelineSubscriptionType.valueOf(type) }?.toSet() ?: emptySet()
            val failType = t.failType?.split(",")?.filter { i -> i.isNotBlank() }
                ?.map { type -> PipelineSubscriptionType.valueOf(type) }?.toSet() ?: emptySet()
            PipelineSetting(
                projectId = t.projectId,
                pipelineId = t.pipelineId,
                pipelineName = t.name,
                desc = t.desc,
                runLockType = PipelineRunLockType.valueOf(t.runLockType),
                successSubscription = Subscription(
                    types = successType,
                    groups = t.successGroup?.split(",")?.toSet() ?: emptySet(),
                    users = t.successReceiver ?: "",
                    wechatGroupFlag = t.successWechatGroupFlag ?: false,
                    wechatGroup = t.successWechatGroup ?: "",
                    wechatGroupMarkdownFlag = t.successWechatGroupMarkdownFlag,
                    detailFlag = t.successDetailFlag,
                    content = t.successContent ?: ""
                ),
                failSubscription = Subscription(
                    types = failType,
                    groups = t.failGroup?.split(",")?.toSet() ?: emptySet(),
                    users = t.failReceiver ?: "",
                    wechatGroupFlag = t.failWechatGroupFlag ?: false,
                    wechatGroup = t.failWechatGroup ?: "",
                    wechatGroupMarkdownFlag = t.failWechatGroupMarkdownFlag ?: false,
                    detailFlag = t.failDetailFlag,
                    content = t.failContent ?: ""
                ),
                labels = emptyList(),
                waitQueueTimeMinute = DateTimeUtil.secondToMinute(t.waitQueueTimeSecond ?: 600000),
                maxQueueSize = t.maxQueueSize,
                maxPipelineResNum = t.maxPipelineResNum,
                maxConRunningQueueSize = t.maxConRunningQueueSize,
                buildNumRule = t.buildNumRule
            )
        } else null
    }

    fun saveSetting(userId: String, setting: PipelineSetting, version: Int) {
        setting.checkParam()

        if (isPipelineExist(
                projectId = setting.projectId,
                excludePipelineId = setting.pipelineId,
                pipelineName = setting.pipelineName
            )) {
            throw ErrorCodeException(
                statusCode = Response.Status.CONFLICT.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NAME_EXISTS,
                defaultMessage = "流水线名称已被使用"
            )
        }

        dslContext.transaction { t ->
            val context = DSL.using(t)
            val old = pipelineSettingDao.getSetting(context, pipelineId = setting.pipelineId)
            pipelineInfoDao.update(
                dslContext = context,
                pipelineId = setting.pipelineId,
                userId = userId,
                updateVersion = false,
                pipelineName = setting.pipelineName,
                pipelineDesc = setting.desc
            )
            if (version > 0) { // #671 兼容无版本要求的修改入口，比如改名，或者只读流水线的修改操作, version=0
                if (old?.maxPipelineResNum != null) {
                    pipelineSettingVersionDao.deleteEarlyVersion(
                        dslContext = context,
                        pipelineId = setting.pipelineId,
                        currentVersion = version,
                        maxPipelineResNum = old.maxPipelineResNum
                    )
                }
                pipelineSettingVersionDao.saveSetting(context, setting, version = version)
            }
            pipelineSettingDao.saveSetting(context, setting).toString()
        }
    }

    fun batchUpdatePipelineModel(
        userId: String,
        pipelineModelVersionList: List<PipelineModelVersion>
    ) {
        pipelineResDao.updatePipelineModel(dslContext, userId, pipelineModelVersionList)
    }

    /**
     * 列出已经删除的流水线
     */
    fun listDeletePipelineIdByProject(projectId: String, days: Long?): List<PipelineInfo> {
        val result = pipelineInfoDao.listDeletePipelineIdByProject(dslContext, projectId, days)
        val list = mutableListOf<PipelineInfo>()
        result?.forEach {
            if (it != null) {
                list.add(pipelineInfoDao.convert(it, null)!!)
            }
        }
        return list
    }

    @Suppress("ThrowsCount")
    fun restorePipeline(
        projectId: String,
        pipelineId: String,
        userId: String,
        channelCode: ChannelCode,
        days: Long?
    ): Model {
        val existModel = getModel(pipelineId) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
            defaultMessage = "流水线编排不存在"
        )
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)

            val pipeline = pipelineInfoDao.getPipelineInfo(
                dslContext = transactionContext,
                projectId = projectId,
                pipelineId = pipelineId,
                channelCode = null,
                delete = true,
                days = days
            ) ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_RESTORE_PIPELINE_NOT_FOUND,
                defaultMessage = "要还原的流水线不存在，可能已经被删除或还原了"
            )

            if (pipeline.channel != channelCode.name) {
                throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = ProcessMessageCode.ERROR_PIPELINE_CHANNEL_CODE,
                    defaultMessage = "指定编辑的流水线渠道来源${pipeline.channel}不符合$channelCode",
                    params = arrayOf(pipeline.channel)
                )
            }

            pipelineInfoDao.restore(
                dslContext = transactionContext,
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId,
                channelCode = channelCode
            )

            // #4012 还原与模板的绑定关系
            templatePipelineDao.restore(dslContext = transactionContext, pipelineId = pipelineId)

            // 只初始化相关信息
            val tasks = initModel(
                model = existModel,
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId,
                create = false,
                channelCode = channelCode
            )
            pipelineModelTaskDao.batchSave(transactionContext, tasks)
        }

        pipelineEventDispatcher.dispatch(
            PipelineRestoreEvent(
                source = "restore_pipeline",
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId
            )
        )

        return existModel
    }

    fun countByPipelineIds(projectId: String, channelCode: ChannelCode, pipelineIds: List<String>): Int {
        return pipelineInfoDao.countByPipelineIds(
            dslContext = dslContext,
            projectId = projectId,
            channelCode = channelCode,
            pipelineIds = pipelineIds
        )
    }

    companion object {
        private const val MAX_LEN_FOR_NAME = 255
        private val logger = LoggerFactory.getLogger(PipelineRepositoryService::class.java)
    }
}
