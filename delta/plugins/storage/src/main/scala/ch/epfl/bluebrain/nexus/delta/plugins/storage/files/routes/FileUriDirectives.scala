package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.server.Directives.parameter
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment

trait FileUriDirectives extends QueryParamsUnmarshalling {

  def storageParam: Directive[Tuple1[Option[IdSegment]]] = parameter("storage".as[IdSegment].?)

}

object FileUriDirectives extends FileUriDirectives
