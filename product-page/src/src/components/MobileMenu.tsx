import * as React from "react"

import ProductList from "./ProductsList"
import GettingStartedList from "./GettingStartedList"
import DevList from "./DevList"

import menuIcon from "../../static/img/icons/menuIcon.svg"
import closeIcon from "../../static/img/icons/closeIcon.svg"

const SubMenu: React.FC<{ title: string; defaultOpened?: boolean }> = ({
  children,
  title,
  defaultOpened = false,
}) => {
  const [open, setOpen] = React.useState(defaultOpened)
  const handleButtonClick = () => {
    setOpen(!open)
  }
  return (
    <>
      <button className="menu-item-button" onClick={handleButtonClick}>
        {title}
      </button>
      {open && <div className="submenu">{children}</div>}
    </>
  )
}

const MobileMenu: React.FC = () => {
  const [open, setOpen] = React.useState(false)

  const openMenu = () => {
    setOpen(true)
  }

  const closeMenu = () => {
    setOpen(false)
  }

  return (
    <nav className="mobile-menu">
      <button className="button" onClick={openMenu}>
        <img src={menuIcon} alt="mobile menu open button" />
      </button>
      <div className={`menu card${open ? " opened" : ""}`}>
        <button className="close-button" onClick={closeMenu}>
          <img src={closeIcon} alt="mobile menu close button" />
        </button>
        <ul>
          <li className="menu-item">
            <SubMenu title="Products" defaultOpened>
              <ProductList />
            </SubMenu>
          </li>
          <li className="menu-item">
            <SubMenu title="Getting Started">
              <GettingStartedList />
            </SubMenu>
          </li>
          <li className="menu-item">
            <SubMenu title="Developers">
              <DevList />
            </SubMenu>
          </li>
        </ul>
      </div>
    </nav>
  )
}

export default MobileMenu
