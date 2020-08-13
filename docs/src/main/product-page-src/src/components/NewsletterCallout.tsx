import * as React from "react"

function serializeFormData(form: HTMLFormElement) {
  return Array.from(form.elements).reduce((obj, field) => {
    const input = field as HTMLInputElement
    if (input.value) {
      obj[input.name] = input.value
    }
    return obj
  }, {} as { [key: string]: string })
}

export enum EMAIL_CATCH_STATUS {
  STANDBY = "normal",
  SUCCESS = "success",
  ERROR = "error",
  PENDING = "pending",
}

const NewsletterCallout: React.FC<{
  status: EMAIL_CATCH_STATUS
  submitEmail: (data: { email?: string }) => Promise<void>
}> = ({ status, submitEmail }) => {
  const formRef = React.useRef<HTMLFormElement>(null)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!formRef.current) {
      return
    }
    let data = serializeFormData(formRef.current)
    data?.email && submitEmail(data)
  }

  return (
    <section className="call-out gradient">
      <div className="container">
        <div className="content">
          <div className="columns">
            <div className="column">
              <b>Never miss an update.</b>
              <p>We won't spam you, and you can unsubscribe in one click.</p>
            </div>
            <div className="column">
              <form
                id="email-catch"
                ref={formRef}
                onSubmit={handleSubmit}
                className={status}
              >
                <div className="state pending">
                  <p>Registering email...</p>
                </div>

                <div className="state success">
                  <p>Thanks! Nice email!</p>
                </div>

                <div className="state error">
                  <p>Sorry, there was a problem signing you up</p>
                </div>

                <div className="state normal">
                  <div className="field has-addons">
                    <div className="control has-icons-left">
                      <input
                        type="email"
                        placeholder="Enter your email"
                        name="email"
                        className="input"
                      />
                    </div>
                    <div className="control">
                      <button type="submit" className="button">
                        Subscribe
                      </button>
                    </div>
                  </div>
                </div>
              </form>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}

export default NewsletterCallout
