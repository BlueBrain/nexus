import * as React from "react"

const Termslist: React.FC = () => {
  return (
    <div>
      <ul className="submenu">
        <li>
          <a href="https://go.epfl.ch/privacy-policy" target="_blank">
            Privacy Policy
          </a>
        </li>
        <li>
          <a
            href="https://www.epfl.ch/about/presidency/presidents-team/legal-affairs/epfl-privacy-policy/cookies-policy/"
            target="_blank"
          >
            Cookie Policy
          </a>
        </li>
        <li>
          <a
            href="https://www.epfl.ch/about/overview/regulations-and-guidelines/disclaimer/"
            target="_blank"
          >
            Disclaimer
          </a>
        </li>
      </ul>
    </div>
  )
}

export default Termslist
