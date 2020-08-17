import * as React from "react"
import ProductList from "./ProductsList"
import GettingStartedList from "./GettingStartedList"
import DevList from "./DevList"

const Dropdown: React.FC<{ title: string }> = ({ children, title }) => {
  const [open, setOpen] = React.useState(false)
  const handleButtonClick = (state?: boolean) => () => {
    setOpen(state || !open)
  }
  return (
    <div className="dropdown-group" onMouseLeave={handleButtonClick(false)}>
      <button
        className="nav-button"
        onClick={handleButtonClick()}
        onMouseEnter={handleButtonClick(true)}
      >
        {title}
      </button>
      <div className={`dropdown card${open ? " opened" : ""}`}>
        <div className="holder" />
        <div className="arrow-up" />
        {children}
      </div>
    </div>
  )
}

const NavMenu: React.FC = () => {
  return (
    <nav className="dropdown-nav">
      <Dropdown title="Products">
        <ProductList />
      </Dropdown>
      <Dropdown title="Getting Started">
        <GettingStartedList />
      </Dropdown>
      <Dropdown title="Developers">
        <DevList />
      </Dropdown>
    </nav>
  )
}

export default NavMenu
