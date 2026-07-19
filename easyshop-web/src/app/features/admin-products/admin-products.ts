import { Component, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { CatalogService } from '../../core/catalog.service';
import { ToastService } from '../../core/toast.service';
import { Category, Product } from '../../core/api-types';

interface ProductRow {
  product: Product;
  categoryName: string;
}

@Component({
  selector: 'app-admin-products',
  imports: [CurrencyPipe],
  templateUrl: './admin-products.html',
  styleUrl: './admin-products.css',
})
export class AdminProducts implements OnInit {
  private readonly catalogService = inject(CatalogService);
  private readonly toast = inject(ToastService);

  protected readonly rows = signal<ProductRow[]>([]);
  protected readonly categories = signal<Category[]>([]);
  protected readonly loading = signal(true);

  protected readonly formOpen = signal(false);
  protected readonly formMode = signal<'add' | 'edit'>('add');
  protected editingProductId = '';

  protected readonly sku = signal('');
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly price = signal('');
  protected readonly categoryId = signal('');

  ngOnInit(): void {
    this.catalogService.listCategories().subscribe((categories) => {
      this.categories.set(categories);
      this.categoryId.set(categories[0]?.id ?? '');
    });
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.catalogService.listProducts(null, 0, 100, true).subscribe((page) => {
      this.rows.set(
        page.content.map((product) => ({
          product,
          categoryName: this.categories().find((c) => c.id === product.categoryId)?.name ?? '',
        })),
      );
      this.loading.set(false);
    });
  }

  categoryName(id: string): string {
    return this.categories().find((c) => c.id === id)?.name ?? '';
  }

  openAddForm(): void {
    this.formMode.set('add');
    this.editingProductId = '';
    this.sku.set('');
    this.name.set('');
    this.description.set('');
    this.price.set('');
    this.categoryId.set(this.categories()[0]?.id ?? '');
    this.formOpen.set(true);
  }

  openEditForm(row: ProductRow): void {
    this.formMode.set('edit');
    this.editingProductId = row.product.id;
    this.sku.set(row.product.sku);
    this.name.set(row.product.name);
    this.description.set(row.product.description);
    this.price.set(String(row.product.price));
    this.categoryId.set(row.product.categoryId);
    this.formOpen.set(true);
  }

  closeForm(): void {
    this.formOpen.set(false);
  }

  submitForm(): void {
    if (!this.sku().trim() || !this.name().trim()) {
      this.toast.show('SKU and name are required');
      return;
    }
    const price = parseFloat(this.price()) || 0;

    if (this.formMode() === 'add') {
      this.catalogService
        .createProduct({
          sku: this.sku(),
          name: this.name(),
          description: this.description(),
          price,
          categoryId: this.categoryId(),
        })
        .subscribe(() => {
          this.toast.show('Product created');
          this.formOpen.set(false);
          this.load();
        });
    } else {
      this.catalogService
        .updateProduct(this.editingProductId, {
          name: this.name(),
          description: this.description(),
          price,
        })
        .subscribe(() => {
          this.toast.show('Product updated');
          this.formOpen.set(false);
          this.load();
        });
    }
  }

  deactivate(row: ProductRow): void {
    this.catalogService.deactivateProduct(row.product.id).subscribe(() => {
      this.toast.show('Product deactivated');
      this.load();
    });
  }
}
