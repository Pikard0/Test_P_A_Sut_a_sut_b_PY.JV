const { expect } = require('@playwright/test');

class RentalPage {
  constructor(page) {
    this.page = page;
    this.rentalResult = page.getByTestId('rental-result');
    this.status = page.getByTestId('rental-status');
    this.total = page.getByTestId('rental-total');
    this.lateFee = page.getByTestId('rental-late-fee');
    this.alert = page.getByRole('alert');
  }

  async open() {
    await this.page.goto('/index.html');
    await expect(this.page.getByRole('heading', { name: 'Обладнання для ваших планів.' })).toBeVisible();
  }

  async createEquipment({ price = 100, stock = 5 } = {}) {
    const code = `E2E-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    const form = this.page.getByTestId('equipment-form');
    await form.getByLabel('Код обладнання', { exact: true }).fill(code);
    await form.getByLabel('Добова ціна, грн', { exact: true }).fill(String(price));
    await form.getByLabel('Залишок', { exact: true }).fill(String(stock));
    await form.getByRole('button', { name: 'Створити обладнання' }).click();
    const result = this.page.getByTestId('equipment-result');
    await expect(result).toBeVisible();
    const text = await result.getByRole('paragraph').textContent();
    const id = Number(text.match(/ID:\s*(\d+)/)[1]);
    return { id, code };
  }

  async createRental({ equipmentId, quantity = 1, days = 2, premium = false } = {}) {
    const reference = `E2E-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    const form = this.page.getByTestId('rental-form');
    await form.getByLabel('Код оренди', { exact: true }).fill(reference);
    await form.getByLabel('Ідентифікатор обладнання', { exact: true }).fill(String(equipmentId));
    await form.getByLabel('Кількість', { exact: true }).fill(String(quantity));
    await form.getByLabel('Кількість днів', { exact: true }).fill(String(days));
    const premiumCheckbox = form.getByLabel('Преміальний клієнт', { exact: true });
    if (premium) await premiumCheckbox.check();
    else await premiumCheckbox.uncheck();
    await form.getByRole('button', { name: 'Оформити оренду' }).click();
    await expect(this.rentalResult).toBeVisible();
    return { id: Number(await this.page.getByTestId('rental-id').textContent()), reference };
  }

  async returnRental(overdueDays) {
    const form = this.page.locator('#return-form');
    await form.getByLabel('Днів прострочення', { exact: true }).fill(String(overdueDays));
    await form.getByRole('button', { name: 'Повернути обладнання' }).click();
    await expect(this.status).toHaveAttribute('data-status', 'Returned');
  }

  async assertActive(totalKopecks) {
    await expect(this.status).toHaveAttribute('data-status', 'Active');
    await expect(this.total).toHaveAttribute('data-kopecks', String(totalKopecks));
  }

  async assertLateFee(feeKopecks) {
    await expect(this.lateFee).toHaveAttribute('data-kopecks', String(feeKopecks));
  }
}

module.exports = { RentalPage };
