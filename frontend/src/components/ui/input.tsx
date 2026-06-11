import * as React from "react"
import { Input as InputPrimitive } from "@base-ui/react/input"

import { cn } from "@/lib/utils"

function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return (
    <InputPrimitive
      type={type}
      data-slot="input"
      className={cn(
        /* 명세 §4.4: min-height 52px, border 1.5px, rounded 12px, px 16px */
        "min-h-[52px] w-full min-w-0 rounded-xl border-[1.5px] border-input bg-transparent px-4 py-3 text-[15px] transition-colors outline-none placeholder:text-muted-foreground focus-visible:border-[color:var(--color-brand-blue)] focus-visible:ring-2 focus-visible:ring-[color:var(--color-brand-blue)]/20 disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-2 aria-invalid:ring-destructive/20",
        className
      )}
      {...props}
    />
  )
}

export { Input }
