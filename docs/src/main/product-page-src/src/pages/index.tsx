import * as React from "react"
import MainLayout from "../layouts/main"
import WhatIsNexus from "../components/WhatIsNexus"
import MainHero from "../components/MainHero"
import WeAreOpenSource from "../components/WeAreOpenSource"
import WhyNexus from "../components/WhyNexus"
import PoweredByNexus from "../components/PoweredByNexus"
import GetStartedWithNexus from "../components/GetStarted"
import AnyQuestions from "../components/AnyQuestions"
import EmailCatch from "../containers/EmailCatch"

export default function Home() {
  return (
    <MainLayout>
      <MainHero />
      <WhatIsNexus />
      <WeAreOpenSource />
      <WhyNexus />
      <PoweredByNexus />
      <GetStartedWithNexus />
      <EmailCatch />
      <AnyQuestions />
    </MainLayout>
  )
}
