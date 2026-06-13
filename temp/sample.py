import tkinter as tk
from tkinter import ttk, messagebox

from invoice_logic import generate_invoice


class InvoiceApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Invoice Generator")
        self.root.geometry("600x500")
        
        # Item entries storage
        self.item_frames = []
        
        # Main frame
        main_frame = ttk.Frame(root, padding="10")
        main_frame.grid(row=0, column=0, sticky=(tk.W, tk.E, tk.N, tk.S))
        
        # Tax Rate
        ttk.Label(main_frame, text="Tax Rate (%):").grid(row=0, column=0, sticky=tk.W, pady=5)
        self.tax_rate_var = tk.StringVar(value="10")
        ttk.Entry(main_frame, textvariable=self.tax_rate_var, width=15).grid(row=0, column=1, sticky=tk.W, pady=5)
        
        # Discount
        ttk.Label(main_frame, text="Discount (%):").grid(row=1, column=0, sticky=tk.W, pady=5)
        self.discount_var = tk.StringVar(value="5")
        ttk.Entry(main_frame, textvariable=self.discount_var, width=15).grid(row=1, column=1, sticky=tk.W, pady=5)
        
        # Items section
        ttk.Label(main_frame, text="Items:", font=('Helvetica', 10, 'bold')).grid(row=2, column=0, columnspan=2, sticky=tk.W, pady=10)
        
        # Items container
        self.items_container = ttk.Frame(main_frame)
        self.items_container.grid(row=3, column=0, columnspan=2, sticky=(tk.W, tk.E), pady=5)
        
        # Add item button
        ttk.Button(main_frame, text="Add Item", command=self.add_item).grid(row=4, column=0, sticky=tk.W, pady=10)
        
        # Generate button
        ttk.Button(main_frame, text="Generate Invoice", command=self.generate).grid(row=4, column=1, sticky=tk.W, pady=10)
        
        # Result text area
        ttk.Label(main_frame, text="Invoice:", font=('Helvetica', 10, 'bold')).grid(row=5, column=0, columnspan=2, sticky=tk.W, pady=5)
        self.result_text = tk.Text(main_frame, height=12, width=60)
        self.result_text.grid(row=6, column=0, columnspan=2, sticky=(tk.W, tk.E, tk.N, tk.S), pady=5)
        
        # Scrollbar for text
        scrollbar = ttk.Scrollbar(main_frame, orient=tk.VERTICAL, command=self.result_text.yview)
        scrollbar.grid(row=6, column=2, sticky=(tk.N, tk.S), pady=5)
        self.result_text.config(yscrollcommand=scrollbar.set)
        
        # Configure grid weights
        main_frame.columnconfigure(1, weight=1)
        
        # Add default items
        self.add_item("apple", "3", "1.50")
        self.add_item("bread", "2", "4.00")
    
    def add_item(self, name="", quantity="", price=""):
        frame = ttk.Frame(self.items_container)
        frame.pack(fill=tk.X, pady=2)
        
        ttk.Label(frame, text="Name:").pack(side=tk.LEFT)
        name_var = tk.StringVar(value=name)
        ttk.Entry(frame, textvariable=name_var, width=15).pack(side=tk.LEFT, padx=2)
        
        ttk.Label(frame, text="Qty:").pack(side=tk.LEFT)
        qty_var = tk.StringVar(value=quantity)
        ttk.Entry(frame, textvariable=qty_var, width=8).pack(side=tk.LEFT, padx=2)
        
        ttk.Label(frame, text="Price:").pack(side=tk.LEFT)
        price_var = tk.StringVar(value=price)
        ttk.Entry(frame, textvariable=price_var, width=10).pack(side=tk.LEFT, padx=2)
        
        ttk.Button(frame, text="X", command=frame.destroy, width=3).pack(side=tk.LEFT, padx=5)
        
        self.item_frames.append((name_var, qty_var, price_var))
    
    def generate(self):
        try:
            tax_rate = float(self.tax_rate_var.get())
            discount = float(self.discount_var.get())
            
            items = []
            for name_var, qty_var, price_var in self.item_frames:
                name = name_var.get().strip()
                quantity = int(qty_var.get())
                unit_price = float(price_var.get())
                
                if name:
                    items.append({
                        "name": name,
                        "quantity": quantity,
                        "unit_price": unit_price
                    })
            
            if not items:
                messagebox.showwarning("Warning", "Please add at least one item.")
                return
            
            invoice = generate_invoice(items, tax_rate, discount)
            self.result_text.delete(1.0, tk.END)
            self.result_text.insert(1.0, invoice)
            
        except ValueError as e:
            messagebox.showerror("Error", "Please enter valid numbers for quantity, price, tax rate, and discount.")


if __name__ == "__main__":
    root = tk.Tk()
    app = InvoiceApp(root)
    root.mainloop()