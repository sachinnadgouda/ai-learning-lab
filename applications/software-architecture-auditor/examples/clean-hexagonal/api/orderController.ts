import { OrderDto } from "../application/orderDto";

export function formatOrderResponse(order: OrderDto): string {
  return JSON.stringify(order);
}
