package ch.epfl.bluebrain.nexus.rdf.graph

import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.graph.CursorOp._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class DecodingErrorSpec extends AnyWordSpecLike with Matchers {

  "A DecodingError" should {
    "return the provided message" in {
      DecodingError("message", Nil).getMessage shouldEqual "message"
      DecodingError("message", Top :: Down(rdf.first) :: Nil).getMessage shouldEqual "message: Top,Down(http://www.w3.org/1999/02/22-rdf-syntax-ns#first)"
    }
  }

}
