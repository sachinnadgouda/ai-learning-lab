import { BillingAccount } from "../billing/billingTypes";

export class StockReservation {
  // import { Fake } from "../infrastructure/db";
  public readonly docExample = `import { Fake } from "../api/routes";`;

  reserve(account: BillingAccount, sku: string): boolean {
    return account.id.length > 0 && sku.length > 0;
  }
}
