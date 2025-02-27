package com.tencent.devops.common.pipeline.pojo

import com.tencent.devops.common.pipeline.pojo.element.atom.ManualReviewParam
import org.junit.Assert
import org.junit.jupiter.api.Test

internal class StagePauseCheckTest {

    @Test
    fun parseReviewParams() {
        val check = StagePauseCheck(
            manualTrigger = true,
            reviewParams = mutableListOf(
                ManualReviewParam(key = "p1", value = "111"),
                ManualReviewParam(key = "p2", value = "222")
            )
        )
        val originKeys = check.reviewParams?.map { it.key }?.toList()
        val params = mutableListOf(
            ManualReviewParam(key = "p1", value = "123"),
            ManualReviewParam(key = "p2", value = "222")
        )
        Assert.assertEquals(
            mutableListOf(ManualReviewParam(key = "p1", value = "123")),
            check.parseReviewParams(params)
        )
        Assert.assertEquals(
            check.reviewParams?.map { it.key }?.toList(),
            originKeys
        )
    }
}
