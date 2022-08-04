package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

class FilterDeprecatedSpec extends PipeBaseSpec {

  "Filter deprecated" ignore {

    "not modify non-deprecated data" in {
      FilterDeprecated.pipe.parseAndRun(None, sampleData).accepted.value shouldEqual sampleData
    }

    "filter out deprecated data" in {
      FilterDeprecated.pipe.parseAndRun(None, sampleData.copy(deprecated = true)).accepted shouldEqual None
    }
  }

}
