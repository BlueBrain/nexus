import * as React from "react"
import MainLayout from "../layouts/Main"
import WhatIsNexus from "../components/WhatIsNexus"
import MainHero from "../components/MainHero"
import WeAreOpenSource from "../components/WeAreOpenSource"
import Features from "../components/Features"
import PoweredByNexus from "../components/PoweredByNexus"
import GetStartedWithNexus from "../components/GetStarted"
import AnyQuestions from "../components/AnyQuestions"
import EmailCatch from "../containers/EmailCatch"

import scienceDriven from "../../static/img/icons/microscope.svg"
import productionReady from "../../static/img/icons/server.svg"
import allInOne from "../../static/img/icons/schema.svg"
import extensible from "../../static/img/icons/puzzle.svg"
import valueFromData from "../../static/img/icons/market.svg"
import versatile from "../../static/img/icons/comic.svg"

const features = [
  {
    title: "Science-Driven",
    image: scienceDriven,
    description:
      "Originally developed to handle data across all levels and scales of the brain, together with scientists and domain experts. Designed for any research applications.",
  },
  {
    title: "Secure, Scalable, and Production-Ready ",
    image: productionReady,
    description:
      "A large organization needs a reliable data platform. That’s why we developed Nexus as an enterprise-grade system.",
  },
  {
    title: "Rich Ecosystem",
    image: allInOne,
    description:
      "By being open-source, our users can access the full suite of libraries, packages, and products that support our ecosystem. In addition to our core products, check out our Javascript libraries, Python SDK, and CLI.",
  },
  {
    title: "Versatile",
    image: versatile,
    description:
      "Evolve your data continuously by adopting the same technologies and standards that support the world wide web.",
  },
  {
    title: "Make Data Computable",
    image: valueFromData,
    description:
      "Nexus makes it possible to work with data at scale. Instantiate real computable objects directly from your data, to power your research and apps.",
  },
  {
    title: "Extensible",
    image: extensible,
    description:
      "Develop your own visualization plugins, data mappers, and use server sent events to create your own integrations.",
  },
]

export default function Home() {
  return (
    <MainLayout>
      <MainHero />
      <WhatIsNexus />
      <WeAreOpenSource />
      <Features
        id="why"
        title="Why Blue Brain Nexus?"
        subtitle="Nexus was born out of a need for better data management in the
            simulation neuroscience field, but built to cover any other use case.
            The data management needs of scientists is something that we value
            and aim to make as easy as possible."
        features={features}
      />
      <PoweredByNexus />
      <GetStartedWithNexus />
      <EmailCatch />
      <AnyQuestions />
    </MainLayout>
  )
}
