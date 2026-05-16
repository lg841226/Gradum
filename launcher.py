"""Gradum Agent Launcher - GUI helper for running the AI agent."""

import subprocess
import sys
import tkinter as tk
from tkinter import ttk, messagebox
from pathlib import Path


class GradumLauncher:
    """Main GUI application for Gradum Agent launcher."""

    def __init__(self, root):
        self.root = root
        self.root.title("Gradum Agent Launcher")
        self.root.geometry("500x350")
        self.root.resizable(True, True)

        self.project_root = Path(__file__).parent
        self.agent_path = self.project_root / "agent.py"

        self.models = [
            "minimax-m2.5:cloud",
            "qwen3.5:397b-cloud",
            "qwen3-coder:480b-cloud"
        ]

        self._setup_ui()

    def _setup_ui(self):
        """Setup the GUI components."""
        main_frame = ttk.Frame(self.root, padding="15")
        main_frame.grid(row=0, column=0, sticky=(tk.W, tk.E, tk.N, tk.S))
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(0, weight=1)

        ttk.Label(main_frame, text="Gradum Agent", 
                 font=("Arial", 16, "bold")).grid(row=0, column=0, pady=10)

        model_frame = ttk.LabelFrame(main_frame, text="Model Selection")
        model_frame.grid(row=1, column=0, sticky=(tk.W, tk.E), pady=8)

        self.model_var = tk.StringVar(value=self.models[0])
        model_combo = ttk.Combobox(model_frame, textvariable=self.model_var,
                                  values=self.models, state="readonly", width=40)
        model_combo.grid(row=0, column=0, padx=8, pady=8)

        self.context_var = tk.BooleanVar()
        context_check = ttk.Checkbutton(main_frame, text="Enable Context Memory",
                                       variable=self.context_var)
        context_check.grid(row=2, column=0, sticky=tk.W, pady=5)

        self.think_var = tk.BooleanVar(value=True)
        think_check = ttk.Checkbutton(main_frame, text="Enable Thinking Mode (--think)",
                                     variable=self.think_var)
        think_check.grid(row=3, column=0, sticky=tk.W, pady=5)

        prompt_frame = ttk.LabelFrame(main_frame, text="Prompt Input")
        prompt_frame.grid(row=4, column=0, sticky=(tk.W, tk.E, tk.N, tk.S), pady=8)
        prompt_frame.columnconfigure(0, weight=1)
        prompt_frame.rowconfigure(0, weight=1)

        self.prompt_text = tk.Text(prompt_frame, wrap=tk.WORD, width=55, height=8)
        self.prompt_text.grid(row=0, column=0, padx=8, pady=8, sticky=(tk.W, tk.E, tk.N, tk.S))

        button_frame = ttk.Frame(main_frame)
        button_frame.grid(row=5, column=0, pady=15)

        self.run_button = ttk.Button(button_frame, text="Run Agent",
                                     command=self.run_agent, width=15)
        self.run_button.grid(row=0, column=0, padx=5)

        self.stop_button = ttk.Button(button_frame, text="Stop",
                                      command=self.stop_agent, state=tk.DISABLED, width=15)
        self.stop_button.grid(row=0, column=1, padx=5)

        main_frame.columnconfigure(0, weight=1)
        main_frame.rowconfigure(4, weight=1)

        self.process = None

    def run_agent(self):
        """Run the agent.py with selected parameters."""
        prompt = self.prompt_text.get(1.0, tk.END).strip()
        if not prompt:
            messagebox.showerror("Error", "Please enter a prompt!")
            return

        cmd = [sys.executable, str(self.agent_path)]
        cmd.extend(["--model", self.model_var.get()])

        if self.think_var.get():
            cmd.append("--think")

        if self.context_var.get():
            cmd.append("--context")

        cmd.append(prompt)

        self.run_button.config(state=tk.DISABLED)
        self.stop_button.config(state=tk.NORMAL)

        try:
            self.process = subprocess.Popen(
                cmd,
                cwd=self.project_root,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                universal_newlines=True
            )
            self.read_output()

        except Exception as e:
            messagebox.showerror("Error", f"Failed to start agent: {str(e)}")
            self.run_button.config(state=tk.NORMAL)
            self.stop_button.config(state=tk.DISABLED)

    def read_output(self):
        """Read subprocess output asynchronously."""
        if self.process:
            line = self.process.stdout.readline()
            if line:
                print(line.strip())
                self.root.after(100, self.read_output)
            else:
                return_code = self.process.poll()
                if return_code is None:
                    self.root.after(100, self.read_output)
                else:
                    self.run_button.config(state=tk.NORMAL)
                    self.stop_button.config(state=tk.DISABLED)
                    self.process = None

    def stop_agent(self):
        """Stop the running agent process."""
        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
            except Exception:
                pass

            self.process = None
            self.run_button.config(state=tk.NORMAL)
            self.stop_button.config(state=tk.DISABLED)


if __name__ == "__main__":
    root = tk.Tk()
    app = GradumLauncher(root)
    root.mainloop()