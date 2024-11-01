package ch.epfl.bluebrain.nexus.delta

import akka.stream.scaladsl.Source
import akka.util.ByteString

package object kernel {

  type AkkaSource = Source[ByteString, Any]

}
