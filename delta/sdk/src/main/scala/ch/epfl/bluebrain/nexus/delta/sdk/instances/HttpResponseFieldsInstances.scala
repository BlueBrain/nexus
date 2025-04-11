package ch.epfl.bluebrain.nexus.delta.sdk.instances

import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tags
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems

trait HttpResponseFieldsInstances {
  implicit val offsetResponseFields: HttpResponseFields[Offset] = HttpResponseFields.defaultOk

  implicit val progressStatisticsResponseFields: HttpResponseFields[ProgressStatistics] = HttpResponseFields.defaultOk

  implicit val remainingElemsHttpResponseFields: HttpResponseFields[RemainingElems] = HttpResponseFields.defaultOk

  implicit val tagsHttpResponseFields: HttpResponseFields[Tags] = HttpResponseFields.defaultOk
}
