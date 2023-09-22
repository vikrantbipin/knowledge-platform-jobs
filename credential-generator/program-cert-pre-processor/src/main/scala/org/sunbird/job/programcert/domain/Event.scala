package org.sunbird.job.programcert.domain

import org.sunbird.job.domain.reader.JobRequest
import org.sunbird.job.programcert.task.ProgramCertPreProcessorConfig

class Event(eventMap: java.util.Map[String, Any], partition: Int, offset: Long)  extends JobRequest(eventMap, partition, offset) {
    
    def action:String = readOrDefault[String]("edata.action", "")

    def batchId: String = readOrDefault[String]("edata.batchId", "")

    def courseId: String = readOrDefault[String]("edata.courseId", "")

    def userId: String = {
        val list = readOrDefault[List[String]]("edata.userIds", List[String]())
        if(list.isEmpty) "" else list.head
    }

    def eData: Map[String, AnyRef] = readOrDefault[Map[String, AnyRef]]("edata", Map[String, AnyRef]())

    def isValid()(config: ProgramCertPreProcessorConfig): Boolean = {
        config.programCertPreProcess.equalsIgnoreCase(action) && !batchId.isEmpty && !courseId.isEmpty &&
          !userId.isEmpty
    }
}
