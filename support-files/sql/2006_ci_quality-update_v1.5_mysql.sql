USE devops_ci_quality;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ci_quality_schema_update;

DELIMITER <CI_UBF>

CREATE PROCEDURE ci_quality_schema_update()
BEGIN

    DECLARE db VARCHAR(100);
    SET AUTOCOMMIT = 0;
    SELECT DATABASE() INTO db;

	IF NOT EXISTS(SELECT 1
                      FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = db
                        AND TABLE_NAME = 'T_QUALITY_HIS_ORIGIN_METADATA'
                        AND COLUMN_NAME = 'CREATE_TIME') THEN
        ALTER TABLE T_QUALITY_HIS_ORIGIN_METADATA ADD COLUMN `CREATE_TIME` BIGINT(20) COMMENT '创建时间';
    END IF;

    IF NOT EXISTS(SELECT 1
                      FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = db
                        AND TABLE_NAME = 'T_QUALITY_HIS_DETAIL_METADATA'
                        AND COLUMN_NAME = 'CREATE_TIME') THEN
        ALTER TABLE T_QUALITY_HIS_DETAIL_METADATA ADD COLUMN `CREATE_TIME` BIGINT(20) COMMENT '创建时间';
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.TABLE_CONSTRAINTS TC
                  WHERE TC.TABLE_NAME = 'T_QUALITY_HIS_DETAIL_METADATA'
                    AND TC.CONSTRAINT_TYPE = 'UNIQUE'
                    AND TC.CONSTRAINT_NAME = 'BUILD_ID_DATA_ID_INDEX') THEN
        TRUNCATE table `T_QUALITY_HIS_DETAIL_METADATA`;
        ALTER TABLE `T_QUALITY_HIS_DETAIL_METADATA`
            ADD UNIQUE INDEX BUILD_ID_DATA_ID_INDEX (`BUILD_ID`, `DATA_ID`);
    END IF;

    IF EXISTS(SELECT 1
              FROM information_schema.STATISTICS TC
              WHERE TC.TABLE_NAME = 'T_QUALITY_HIS_DETAIL_METADATA'
                AND TC.INDEX_NAME = 'BUILD_DATA_ID_INDEX') THEN
        ALTER TABLE T_QUALITY_HIS_DETAIL_METADATA
            DROP INDEX BUILD_DATA_ID_INDEX;
    END IF;

    IF EXISTS(SELECT 1
              FROM information_schema.STATISTICS TC
              WHERE TC.TABLE_NAME = 'T_QUALITY_HIS_DETAIL_METADATA'
                AND TC.INDEX_NAME = 'BUILD_ID_INDEX') THEN
        ALTER TABLE T_QUALITY_HIS_DETAIL_METADATA
            DROP INDEX BUILD_ID_INDEX;
    END IF;

    COMMIT;
END <CI_UBF>
DELIMITER ;
COMMIT;
CALL ci_quality_schema_update();
