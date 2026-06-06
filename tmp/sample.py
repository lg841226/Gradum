def compute_price(quantity, unit_price):
    """Calculate total price without tax."""
    total_amount = quantity * unit_price
    return total_amount

def apply_discount(amount: float, discount_percent: float) -> float:
    """Apply discount to amount and return discounted price."""
    discount = amount * (discount_percent / 100)
    return amount - discount

def format_currency(amount: float) -> str:
    """Format amount as currency string."""
    return f"${amount:.2f}"

def calculate_price_with_tax(quantity, unit_price, tax_rate):
    """Calculate total price including tax."""
    base_amount = quantity * unit_price
    tax = base_amount * tax_rate
    return base_amount + tax

ITEMS = [
    {"name": "apple", "quantity": 3, "unit_price": 1.50},
    {"name": "bread", "quantity": 2, "unit_price": 4.00},
]

def format_report(items: list[dict]):
    """Format items as a price report."""
    lines = ["Price Report:"]
    for item in items:
        total = compute_price(item["quantity"], item["unit_price"])
        lines.append(f"  {item['name']}: ${total:.2f}")
    return "\n".join(lines)