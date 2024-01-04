package org.sunbird.job.karmapoints.functions

import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.karmapoints.domain.Event
import org.sunbird.job.karmapoints.task.KarmaPointsProcessorConfig
import org.sunbird.job.karmapoints.util.Utility
import org.sunbird.job.util.{CassandraUtil, HttpUtil, JSONUtil}
import org.sunbird.job.{BaseProcessFunction, Metrics}

import java.time.{LocalDate, ZoneOffset}
import java.util

class KarmaPointsCourseCompletionProcessorFn(config: KarmaPointsProcessorConfig, httpUtil: HttpUtil)
                                            (implicit val stringTypeInfo: TypeInformation[String],
                                @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessFunction[Event, String](config)   {

  private[this] val logger = LoggerFactory.getLogger(classOf[KarmaPointsCourseCompletionProcessorFn])
  lazy private val mapper: ObjectMapper = new ObjectMapper()
  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
  }

  override def close(): Unit = {
    cassandraUtil.close()
    super.close()
  }

  override def metricsList(): List[String] = {
    List(config.totalEventsCount, config.dbReadCount, config.dbUpdateCount, config.failedEventCount, config.skippedEventCount, config.successEventCount,
      config.cacheHitCount, config.karmaPointsIssueEventsCount, config.cacheMissCount)
  }

  override def processElement(event: Event,
                              context: ProcessFunction[Event, String]#Context,
                              metrics: Metrics): Unit = {
    val userId = event.getMap().get(config.EDATA).asInstanceOf[scala.collection.immutable.HashMap[String, Any]]
      .get(config.USERIDS).asInstanceOf[Option[List[Any]]].get(0).asInstanceOf[String]
    val courseId : Option[Any] = event.getMap().get(config.EDATA).asInstanceOf[scala.collection.immutable.HashMap[String, Any]]
      .get(config.COURSE_ID)
    val contextId: String = courseId match {
      case Some(value) => value.asInstanceOf[String]
      case None => ""
    }
    val hierarchy: java.util.Map[String, AnyRef] = Utility.fetchContentHierarchy(contextId, config, cassandraUtil)(metrics)
    val contextType = hierarchy.get(config.PRIMARY_CATEGORY).asInstanceOf[String] // Replace YourTypeForPrimaryCategory with the actual type
    if(!"Course".equals(contextType))
      return
    if(!nonACBPCourseMonthCutOffAvbl(userId, contextType, config, cassandraUtil)(metrics))
      return
    if (Utility.isEntryAlreadyExist(userId, contextType, config.OPERATION_COURSE_COMPLETION, contextId, config, cassandraUtil))
      return
    Utility.courseCompletion(userId, contextType,config.OPERATION_COURSE_COMPLETION,contextId, hierarchy,config, httpUtil, cassandraUtil)(metrics)
  }

  def nonACBPCourseMonthCutOffAvbl(userId: String, contextType: String,
                                   config: KarmaPointsProcessorConfig, cassandraUtil: CassandraUtil)(metrics: Metrics): Boolean = {
    val currentDate = LocalDate.now
    val firstDateOfMonth = currentDate.withDayOfMonth(1)
    val milliseconds = firstDateOfMonth.atStartOfDay.toInstant(ZoneOffset.UTC).toEpochMilli
    val res = Utility.fetchKarmaPointsByCreditDateRange(milliseconds, userId, contextType, config.OPERATION_COURSE_COMPLETION, config, cassandraUtil)
    var index: Int = 0
    var currentMonthCourseQuota: Int = 0
    while (index < res.size()) {
      if (config.OPERATION_COURSE_COMPLETION.equals(res.get(index).getString("operation_type"))) {
        val addInfo = res.get(index).getString(config.ADD_INFO)
        if (!StringUtils.isEmpty(addInfo)) {
          val addInfoMap = JSONUtil.deserialize[java.util.Map[String, Any]](addInfo)
          if (addInfoMap.get(config.ADDINFO_ACBP) == java.lang.Boolean.FALSE) {
            currentMonthCourseQuota += 1
          }
        }
      }
      index += 1
    }
    currentMonthCourseQuota < config.nonAcbpCourseQuota
  }

}
