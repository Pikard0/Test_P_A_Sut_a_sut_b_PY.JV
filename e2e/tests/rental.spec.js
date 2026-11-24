const { test, expect } = require('@playwright/test');
const { RentalPage } = require('../pages/rental-page');

test('creates a rental through the UI and shows the saved result', async ({ page }) => {
  const rental = new RentalPage(page);
  await rental.open();
  const equipment = await rental.createEquipment({ price: 100, stock: 5 });
  await rental.createRental({ equipmentId: equipment.id, quantity: 2, days: 3 });
  await rental.assertActive(60_000);
});

test('completes the return business action through the UI', async ({ page }) => {
  const rental = new RentalPage(page);
  await rental.open();
  const equipment = await rental.createEquipment({ price: 100, stock: 5 });
  await rental.createRental({ equipmentId: equipment.id, quantity: 2, days: 2 });
  await rental.returnRental(4);
  await rental.assertLateFee(15_000);
});

test('shows a backend business refusal for a repeated return', async ({ page }) => {
  const rental = new RentalPage(page);
  await rental.open();
  const equipment = await rental.createEquipment({ price: 100, stock: 5 });
  await rental.createRental({ equipmentId: equipment.id });
  await rental.returnRental(0);
  await page.getByRole('button', { name: 'Повернути обладнання' }).click();
  await expect(rental.alert).toContainText('вже повернено');
});

test('checks both cases of the personal late-fee rule', async ({ page }) => {
  const rental = new RentalPage(page);
  await rental.open();
  const regularEquipment = await rental.createEquipment({ price: 100, stock: 5 });
  await rental.createRental({ equipmentId: regularEquipment.id, days: 2, premium: false });
  await rental.returnRental(4);
  await rental.assertLateFee(7_500);

  const premiumEquipment = await rental.createEquipment({ price: 100, stock: 5 });
  await rental.createRental({ equipmentId: premiumEquipment.id, days: 2, premium: true });
  await rental.returnRental(4);
  await rental.assertLateFee(2_500);
});

test('restores the saved rental after a page reload', async ({ page }) => {
  const rental = new RentalPage(page);
  await rental.open();
  const equipment = await rental.createEquipment({ price: 100, stock: 5 });
  const created = await rental.createRental({ equipmentId: equipment.id, days: 2 });
  await expect(page).toHaveURL(new RegExp(`rental=${created.id}`));
  await page.reload();
  await expect(rental.rentalResult).toBeVisible();
  await rental.assertActive(20_000);
});
