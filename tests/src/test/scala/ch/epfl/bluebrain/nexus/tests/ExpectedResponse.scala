package ch.epfl.bluebrain.nexus.tests

import akka.http.scaladsl.model.StatusCode
import io.circe.Json

final case class ExpectedResponse(statusCode: StatusCode, json: Json)
