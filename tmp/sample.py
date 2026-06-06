def compute_price(quantity, unit_price):
    """计算不含税的总价。"""
    total_amount = quantity * unit_price
    return total_amount

def apply_discount(amount: float, discount_percent: float) -> float:
    """对金额应用折扣并返回折后价。"""
    discount = amount * (discount_percent / 100)
    return amount - discount

def format_currency(amount: float) -> str:
    """将金额格式化为货币字符串。"""
    return f"${amount:.2f}"

def calculate_price_with_tax(quantity, unit_price, tax_rate):
    """计算含税的总价。"""
    base_amount = quantity * unit_price
    tax = base_amount * tax_rate
    return base_amount + tax

ITEMS = [
    {"name": "apple", "quantity": 3, "unit_price": 1.50},
    {"name": "bread", "quantity": 2, "unit_price": 4.00},
]

def format_report(items: list[dict]):
    """将商品格式化为价格报告。"""
    lines = ["Price Report:"]
    for item in items:
        total = compute_price(item["quantity"], item["unit_price"])
        lines.append(f"  {item['name']}: ${total:.2f}")
    return "\n".join(lines)


def generate_invoice(items: list[dict], tax_rate: float, discount: float = 0.0) -> str:
    """
    从商品列表生成发票。
    
    参数:
        items: 包含 'name', 'quantity', 'unit_price' 的商品列表
        tax_rate: 税率百分比（例如 10 表示 10%）
        discount: 可选的折扣百分比（默认为 0）
    
    返回:
        格式化的发票字符串
    """
    lines = ["Invoice:", "-" * 40]
    grand_total = 0.0
    
    for item in items:
        name = item["name"]
        quantity = item["quantity"]
        unit_price = item["unit_price"]
        
        # 原始价格
        original_price = quantity * unit_price
        
        # 折后价格
        discounted_price = apply_discount(original_price, discount) if discount > 0 else original_price
        
        # 含税价格
        tax_amount = discounted_price * (tax_rate / 100)
        price_with_tax = discounted_price + tax_amount
        
        grand_total += price_with_tax
        
        lines.append(f"Item: {name}")
        lines.append(f"  Original: {format_currency(original_price)}")
        lines.append(f"  Discounted: {format_currency(discounted_price)}")
        lines.append(f"  With Tax: {format_currency(price_with_tax)}")
        lines.append("-" * 40)
    
    lines.append(f"Grand Total: {format_currency(grand_total)}")
    return "\n".join(lines)