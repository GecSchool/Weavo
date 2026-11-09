import { Button, type ButtonProps } from "./Button";
import { Icon, type IconName } from "./Icon";
type IconButtonProps = Omit<ButtonProps, "children" | "size" | "aria-label"> & {
  icon: IconName;
  /** Accessible action name. */ label: string;
};
export function IconButton({
  icon,
  label,
  variant = "ghost",
  ...props
}: IconButtonProps) {
  return (
    <Button {...props} size="icon" variant={variant} aria-label={label}>
      <Icon name={icon} />
    </Button>
  );
}
