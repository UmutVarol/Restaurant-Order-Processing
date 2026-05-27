# Restaurant Order Processing

This project is a Java Swing desktop application developed for the `Restaurant Order Processing` topic in SWE312.

## Features

- Customer, table/address, and order type input
- Menu filtering by category
- Add/remove items from cart
- Automatic subtotal, tax, and total calculation
- Order placement with validation
- Order tracking and status progression
- Object-oriented structure with model, service, and UI layers

## Project Structure

```text
src/com/swe312/restaurant/
|-- Main.java
|-- model/
|   |-- Category.java
|   |-- MenuItem.java
|   |-- Order.java
|   |-- OrderItem.java
|   |-- OrderStatus.java
|   `-- OrderType.java
|-- service/
|   `-- RestaurantService.java
`-- ui/
    `-- RestaurantOrderFrame.java
```

## How to Compile

```powershell
javac -d out src\com\swe312\restaurant\Main.java src\com\swe312\restaurant\model\*.java src\com\swe312\restaurant\service\*.java src\com\swe312\restaurant\ui\*.java
```

## How to Run

```powershell
java -cp out com.swe312.restaurant.Main
```

## OOP Concepts Used

- `Encapsulation`: Order, menu, and item data are wrapped in classes.
- `Abstraction`: The service layer handles business logic separately from the GUI.
- `Enum usage`: Category, order type, and status are modeled cleanly.
- `Composition`: An order contains multiple order items.
