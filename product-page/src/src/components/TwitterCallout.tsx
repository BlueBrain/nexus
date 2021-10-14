import * as React from "react"

export default function TwitterCallout() {

  return (
    <section className="call-out gradient">
      <div className="container">
        <div className="content">
          <div className="columns">
            <div className="column">
              <b>Never miss an update.</b>
              <p>
                Follow the Blue Brain Project on Twitter for the latest news and
                releases.
              </p>
            </div>
            <div className="column" style={{margin:'auto'}}>
              <script
                type="text/javascript"
                async
                src="https://platform.twitter.com/widgets.js"
              ></script>
              <a
                href="https://twitter.com/BlueBrainPjt?ref_src=twsrc%5Etfw"
                className="twitter-follow-button"
                data-size="large"
                data-lang="en"
                data-dnt="true"
                data-show-count="false"
              >
                Follow @BlueBrainPjt
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}